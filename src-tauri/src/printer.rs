use crate::image_ops;
use crate::models::PrintRequest;
use crate::ps_bridge::run_powershell_with_json;
use std::fs;
use uuid::Uuid;

pub fn print_pages(req: PrintRequest) -> Result<String, String> {
    if req.page_paths.is_empty() {
        return Err("No pages provided to print.".to_string());
    }
    for p in &req.page_paths {
        if !std::path::Path::new(p).exists() {
            return Err(format!("Print page file not found: {p}"));
        }
    }

    let script = r#"
$cfg = [System.IO.File]::ReadAllText($InputJsonPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
Add-Type -AssemblyName System.Drawing

$doc = New-Object System.Drawing.Printing.PrintDocument
$doc.PrinterSettings.PrinterName = [string]$cfg.printer_name
if (-not $doc.PrinterSettings.IsValid) {
    throw "Printer '$($cfg.printer_name)' is not valid or unavailable."
}

$doc.DocumentName = if ($cfg.job_name) { [string]$cfg.job_name } else { 'AlPrint Document' }
$copies = [int]$cfg.copies
if ($copies -lt 1) { $copies = 1 }
$doc.PrinterSettings.Copies = [int16]([math]::Min([int]$doc.PrinterSettings.MaximumCopies, $copies))

if ($doc.PrinterSettings.CanDuplex) {
    switch ($cfg.duplex) {
        'long_edge'  { $doc.PrinterSettings.Duplex = [System.Drawing.Printing.Duplex]::Vertical }
        'short_edge' { $doc.PrinterSettings.Duplex = [System.Drawing.Printing.Duplex]::Horizontal }
        default      { $doc.PrinterSettings.Duplex = [System.Drawing.Printing.Duplex]::Simplex }
    }
}

if ($cfg.paper_size_name) {
    $wanted = ([string]$cfg.paper_size_name).Trim()
    $matched = $null
    foreach ($sz in $doc.PrinterSettings.PaperSizes) {
        if ($sz.PaperName -ieq $wanted) {
            $matched = $sz
            break
        }
    }
    if (-not $matched) {
        $normWanted = ($wanted.ToLower() -replace '[^a-z0-9]', '')
        foreach ($sz in $doc.PrinterSettings.PaperSizes) {
            $normName = (([string]$sz.PaperName).ToLower() -replace '[^a-z0-9]', '')
            if ($normName -eq $normWanted -or $normName.Contains($normWanted) -or $normWanted.Contains($normName)) {
                $matched = $sz
                break
            }
        }
    }
    if ($matched) {
        $doc.DefaultPageSettings.PaperSize = $matched
    }
}

$doc.DefaultPageSettings.Landscape = [bool]$cfg.landscape
$doc.DefaultPageSettings.Color = [bool]$cfg.color
# Disable .NET's 1-inch (100 hundredths of an inch) default margins so AlPrint controls margins explicitly
$doc.OriginAtMargins = $false
$doc.DefaultPageSettings.Margins = New-Object System.Drawing.Printing.Margins(0, 0, 0, 0)

$script:pageIdx = 0
$script:paths = @($cfg.page_paths)
$script:fitMode = if ($cfg.fit_mode) { [string]$cfg.fit_mode } else { 'fit' }
$script:borderless = [bool]$cfg.borderless
$script:forceGray = (-not [bool]$cfg.color)
$script:marginMm = $cfg.margin_mm
$rawScale = if ($null -ne $cfg.scale_percent) { [int]$cfg.scale_percent } else { 100 }
$script:scalePct = [math]::Max(25, [math]::Min(100, $rawScale))

$doc.add_PrintPage({
    param($sender, $e)
    if ($script:pageIdx -ge $script:paths.Count) {
        $e.HasMorePages = $false
        return
    }
    $path = $script:paths[$script:pageIdx]
    $img = [System.Drawing.Image]::FromFile($path)
    try {
        $g = $e.Graphics
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality

        $pageBounds = [System.Drawing.RectangleF]$e.PageBounds

        $pa = $e.PageSettings.PrintableArea
        if ($e.PageSettings.Landscape) {
            $printable = New-Object System.Drawing.RectangleF($pa.Y, $pa.X, $pa.Height, $pa.Width)
        } else {
            $printable = New-Object System.Drawing.RectangleF($pa.X, $pa.Y, $pa.Width, $pa.Height)
        }
        if ($printable.Width -le 0 -or $printable.Height -le 0) {
            $printable = $pageBounds
        }

        if ($script:borderless -or ($null -ne $script:marginMm -and [double]$script:marginMm -le 0.01)) {
            $bounds = $pageBounds
        } elseif ($null -ne $script:marginMm -and [double]$script:marginMm -gt 0) {
            $m = [float]([double]$script:marginMm / 0.254)
            $insetW = [math]::Max(10.0, [double]$pageBounds.Width - 2.0 * $m)
            $insetH = [math]::Max(10.0, [double]$pageBounds.Height - 2.0 * $m)
            $userRect = New-Object System.Drawing.RectangleF($m, $m, [float]$insetW, [float]$insetH)
            $intersected = [System.Drawing.RectangleF]::Intersect($userRect, $printable)
            if ($intersected.Width -gt 10 -and $intersected.Height -gt 10) {
                $bounds = $intersected
            } else {
                $bounds = $userRect
            }
        } else {
            if ($script:fitMode -eq 'fill') {
                $bounds = $pageBounds
            } else {
                $bounds = $printable
                # If a virtual/driver printer reports 0 hardware margin, apply a clean 3 mm (12/100 in) inset
                if ($bounds.X -lt 1 -and $bounds.Width -ge ($pageBounds.Width - 1) -and $pageBounds.Width -gt 60 -and $pageBounds.Height -gt 60) {
                    $bounds = New-Object System.Drawing.RectangleF(12.0, 12.0, [float]($pageBounds.Width - 24.0), [float]($pageBounds.Height - 24.0))
                }
            }
        }

        $iw = [double]$img.Width
        $ih = [double]$img.Height
        $bw = [double]$bounds.Width
        $bh = [double]$bounds.Height
        $userScale = [double]$script:scalePct / 100.0

        if ($script:fitMode -eq 'fill') {
            $scale = [math]::Max($bw / $iw, $bh / $ih) * $userScale
        } else {
            $scale = [math]::Min($bw / $iw, $bh / $ih) * $userScale
        }
        $dw = [float]($iw * $scale)
        $dh = [float]($ih * $scale)
        $dx = [float]($bounds.X + ($bw - $dw) / 2.0)
        $dy = [float]($bounds.Y + ($bh - $dh) / 2.0)

        $prevClip = $g.Clip
        $g.SetClip($bounds)
        $destRect = New-Object System.Drawing.RectangleF($dx, $dy, $dw, $dh)
        if ($script:forceGray) {
            $ia = New-Object System.Drawing.Imaging.ImageAttributes
            $cm = New-Object System.Drawing.Imaging.ColorMatrix(@(
                @(0.30, 0.30, 0.30, 0, 0),
                @(0.59, 0.59, 0.59, 0, 0),
                @(0.11, 0.11, 0.11, 0, 0),
                @(0,    0,    0,    1, 0),
                @(0,    0,    0,    0, 1)
            ))
            $ia.SetColorMatrix($cm)
            $g.DrawImage($img, [System.Drawing.Rectangle]::Round($destRect), 0, 0, $img.Width, $img.Height, [System.Drawing.GraphicsUnit]::Pixel, $ia)
            $ia.Dispose()
        } else {
            $g.DrawImage($img, $destRect)
        }
        $g.Clip = $prevClip
    } finally {
        $img.Dispose()
    }

    $script:pageIdx++
    $e.HasMorePages = ($script:pageIdx -lt $script:paths.Count)
})

$doc.PrintController = New-Object System.Drawing.Printing.StandardPrintController
$doc.Print()
$doc.Dispose()
Write-Output "Printed $($script:paths.Count) page(s) to $($cfg.printer_name)"
"#;

    run_powershell_with_json(script, &req)
}

pub fn print_test_page(printer_name: &str) -> Result<String, String> {
    let tmp = std::env::temp_dir().join(format!("alprint_testpage_{}.png", Uuid::new_v4()));
    image_ops::generate_test_page_png(printer_name, &tmp)?;

    let req = PrintRequest {
        printer_name: printer_name.to_string(),
        page_paths: vec![tmp.to_string_lossy().to_string()],
        copies: 1,
        landscape: false,
        paper_size_name: "A4".to_string(),
        color: true,
        duplex: "simplex".to_string(),
        borderless: false,
        fit_mode: "fit".to_string(),
        job_name: Some("AlPrint Diagnostic Test Page".to_string()),
        margin_mm: Some(8.0),
        scale_percent: Some(100),
    };

    let res = print_pages(req);
    let _ = fs::remove_file(&tmp);
    res
}
