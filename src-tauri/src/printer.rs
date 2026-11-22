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

$doc.DefaultPageSettings.Landscape = [bool]$cfg.landscape
$doc.DefaultPageSettings.Color = [bool]$cfg.color
if ($cfg.borderless) {
    $doc.OriginAtMargins = $false
    $doc.DefaultPageSettings.Margins = New-Object System.Drawing.Printing.Margins(0, 0, 0, 0)
}

if ($cfg.paper_size_name) {
    $wanted = [string]$cfg.paper_size_name
    foreach ($sz in $doc.PrinterSettings.PaperSizes) {
        if ($sz.PaperName -eq $wanted) {
            $doc.DefaultPageSettings.PaperSize = $sz
            break
        }
    }
}

$script:pageIdx = 0
$script:paths = @($cfg.page_paths)
$script:fitMode = if ($cfg.fit_mode) { [string]$cfg.fit_mode } else { 'fit' }
$script:borderless = [bool]$cfg.borderless
$script:forceGray = (-not [bool]$cfg.color)

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

        $bounds = if ($script:borderless) { $e.PageBounds } else { $e.MarginBounds }
        if ($bounds.Width -le 0 -or $bounds.Height -le 0) { $bounds = $e.PageBounds }

        $iw = [double]$img.Width
        $ih = [double]$img.Height
        $bw = [double]$bounds.Width
        $bh = [double]$bounds.Height

        if ($script:fitMode -eq 'fill') {
            $scale = [math]::Max($bw / $iw, $bh / $ih)
        } else {
            $scale = [math]::Min($bw / $iw, $bh / $ih)
        }
        $dw = [float]($iw * $scale)
        $dh = [float]($ih * $scale)
        $dx = [float]($bounds.X + ($bw - $dw) / 2.0)
        $dy = [float]($bounds.Y + ($bh - $dh) / 2.0)

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
    };

    let res = print_pages(req);
    let _ = fs::remove_file(&tmp);
    res
}
