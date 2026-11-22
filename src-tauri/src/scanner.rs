use crate::image_ops;
use crate::models::{CropRect, PageEdits, ScanPage, ScanRequest};
use crate::ps_bridge::run_powershell_with_json;
use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use image::{DynamicImage, GenericImageView};
use serde::Serialize;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;
use uuid::Uuid;

pub fn session_dir(session_id: &str) -> Result<PathBuf, String> {
    let base = dirs::data_local_dir().unwrap_or_else(std::env::temp_dir);
    let clean_id = image_ops::sanitize_filename(session_id);
    let dir = base.join("AlPrint").join("sessions").join(clean_id);
    fs::create_dir_all(&dir).map_err(|e| format!("Failed to create session dir: {e}"))?;
    Ok(dir)
}

pub async fn scan_page(req: ScanRequest) -> Result<Vec<ScanPage>, String> {
    let captured_path = if req.backend == "escl" {
        let ep = req
            .endpoint
            .clone()
            .ok_or_else(|| "Missing eSCL endpoint URL".to_string())?;
        scan_via_escl(&ep, &req).await?
    } else {
        let req_clone = req.clone();
        tokio::task::spawn_blocking(move || scan_via_wia(&req_clone))
            .await
            .map_err(|e| format!("Scan task join error: {e}"))??
    };

    let raw_img = image::open(&captured_path)
        .map_err(|e| format!("Failed to load captured scan image: {e}"))?;
    let _ = fs::remove_file(&captured_path);

    let mut processed_imgs: Vec<(DynamicImage, String, Option<CropRect>)> = Vec::new();
    match req.preset.as_str() {
        "multi_item" => {
            let parts = image_ops::split_multi_items(&raw_img);
            let total = parts.len();
            for (idx, p) in parts.into_iter().enumerate() {
                processed_imgs.push((p, format!("Multi-Item {}/{}", idx + 1, total), None));
            }
        }
        "book_spread" => {
            let parts = image_ops::split_book_spread(&raw_img);
            for (idx, p) in parts.into_iter().enumerate() {
                let side = if idx == 0 { "Book Left" } else { "Book Right" };
                processed_imgs.push((p, side.to_string(), None));
            }
        }
        "id_card" => {
            let final_img = if req.detect_edges {
                image_ops::detect_edges_and_crop(&raw_img)
            } else {
                raw_img
            };
            processed_imgs.push((final_img, "ID Capture".to_string(), None));
        }
        _ => {
            let suggested = if req.detect_edges {
                image_ops::detect_edges_rect(&raw_img)
            } else {
                None
            };
            let label = if req.preset == "photo" {
                "Photo Scan"
            } else {
                "Scanner"
            };
            processed_imgs.push((raw_img, label.to_string(), suggested));
        }
    }

    let dir = session_dir(&req.session_id)?;
    let mut pages = Vec::new();
    for (img, label, suggested_crop) in processed_imgs {
        let edits = PageEdits {
            crop: None,
            rotate_deg: 0,
            brightness: 0,
            contrast: 0,
            saturation: 0,
            filter: if req.auto_enhance {
                "auto_enhance".to_string()
            } else {
                "none".to_string()
            },
        };
        let page = persist_page_to_session(
            &dir,
            &req.session_id,
            &img,
            edits,
            req.dpi,
            &label,
            suggested_crop,
        )?;
        pages.push(page);
    }

    Ok(pages)
}

#[derive(Serialize)]
struct WiaScanInput {
    device_id: String,
    source: String,
    page_size: String,
    color_mode: String,
    dpi: u32,
    brightness: i32,
    contrast: i32,
    out_path: String,
}

fn scan_via_wia(req: &ScanRequest) -> Result<PathBuf, String> {
    let temp_bmp = std::env::temp_dir().join(format!("alprint_wia_{}.bmp", Uuid::new_v4()));
    let input = WiaScanInput {
        device_id: req.scanner_id.clone(),
        source: req.source.clone(),
        page_size: req.page_size.clone(),
        color_mode: req.color_mode.clone(),
        dpi: req.dpi,
        brightness: req.brightness,
        contrast: req.contrast,
        out_path: temp_bmp.to_string_lossy().to_string(),
    };

    let script = r#"
$cfg = [System.IO.File]::ReadAllText($InputJsonPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$dm = New-Object -ComObject WIA.DeviceManager
$targetInfo = $null
foreach ($d in $dm.DeviceInfos) {
    if ([string]$d.DeviceID -eq [string]$cfg.device_id) {
        $targetInfo = $d
        break
    }
}
if (-not $targetInfo) {
    foreach ($d in $dm.DeviceInfos) {
        if ($d.Type -eq 1) { $targetInfo = $d; break }
    }
}
if (-not $targetInfo) {
    throw 'No WIA scanner device found. Make sure the scanner is powered on and connected.'
}

$dev = $targetInfo.Connect()

# Select Flatbed or ADF (Document Handling Select: 2 = Flatbed, 1 = Feeder)
if ($cfg.source -eq 'adf') {
    try {
        foreach ($dp in $dev.Properties) {
            if ($dp.PropertyID -eq 3088) {
                $caps = [int]$dp.Value
                if (($caps -band 1) -eq 0) {
                    throw 'This scanner does not have an Automatic Document Feeder (ADF).'
                }
            }
        }
        foreach ($dp in $dev.Properties) {
            if ($dp.PropertyID -eq 3097) { $dp.Value = 1 }
        }
    } catch {
        if ($_.Exception.Message -like '*does not have*') { throw }
    }
} else {
    try {
        foreach ($dp in $dev.Properties) {
            if ($dp.PropertyID -eq 3097) { $dp.Value = 2 }
        }
    } catch {}
}

$item = $dev.Items.Item(1)

# 1. Color intent (6146) + BPP fallback (4104)
$intent = 1
$bpp = 24
if ($cfg.color_mode -eq 'Grayscale') { $intent = 2; $bpp = 8 }
elseif ($cfg.color_mode -eq 'Black & White') { $intent = 4; $bpp = 1 }

try {
    $pIntent = $item.Properties | Where-Object { $_.PropertyID -eq 6146 } | Select-Object -First 1
    if ($pIntent) { $pIntent.Value = $intent }
} catch {}
try {
    $pBpp = $item.Properties | Where-Object { $_.PropertyID -eq 4104 } | Select-Object -First 1
    if ($pBpp) { $pBpp.Value = $bpp }
} catch {}

# 2. Snap DPI to nearest supported value (6147 / 6148)
$chosenDpi = [int]$cfg.dpi
if ($chosenDpi -lt 75) { $chosenDpi = 200 }
try {
    $pRes = $item.Properties | Where-Object { $_.PropertyID -eq 6147 } | Select-Object -First 1
    if ($pRes -and $pRes.SubType -eq 2) {
        $best = $chosenDpi
        $bestDiff = 999999
        foreach ($v in $pRes.SubTypeValues) {
            $diff = [math]::Abs([int]$v - $chosenDpi)
            if ($diff -lt $bestDiff) { $bestDiff = $diff; $best = [int]$v }
        }
        $chosenDpi = $best
    }
} catch {}

foreach ($pidVal in @(6147, 6148)) {
    try {
        $p = $item.Properties | Where-Object { $_.PropertyID -eq $pidVal } | Select-Object -First 1
        if ($p) { $p.Value = $chosenDpi }
    } catch {}
}

# 3. Start Position (6149, 6150) & Extent (6151, 6152)
foreach ($pidZero in @(6149, 6150)) {
    try {
        $p = $item.Properties | Where-Object { $_.PropertyID -eq $pidZero } | Select-Object -First 1
        if ($p) { $p.Value = 0 }
    } catch {}
}

$wIn = 8.5
$hIn = 11.69
switch ($cfg.page_size) {
    'a4' { $wIn = 8.27; $hIn = 11.69 }
    'letter' { $wIn = 8.5; $hIn = 11.0 }
    '4x6' { $wIn = 4.0; $hIn = 6.0 }
    '5x7' { $wIn = 5.0; $hIn = 7.0 }
    default { $wIn = 0; $hIn = 0 }
}

$pW = $item.Properties | Where-Object { $_.PropertyID -eq 6151 } | Select-Object -First 1
$pH = $item.Properties | Where-Object { $_.PropertyID -eq 6152 } | Select-Object -First 1

if ($pW) {
    $maxW = if ($pW.SubType -eq 1) { [int]$pW.SubTypeMax } else { [int]([math]::Round(8.5 * $chosenDpi)) }
    $targetW = if ($wIn -gt 0) { [int][math]::Round($wIn * $chosenDpi) } else { $maxW }
    $pW.Value = [math]::Max(16, [math]::Min($maxW, $targetW))
}
if ($pH) {
    $maxH = if ($pH.SubType -eq 1) { [int]$pH.SubTypeMax } else { [int]([math]::Round(11.69 * $chosenDpi)) }
    $targetH = if ($hIn -gt 0) { [int][math]::Round($hIn * $chosenDpi) } else { $maxH }
    $pH.Value = [math]::Max(16, [math]::Min($maxH, $targetH))
}

# 4. Brightness (6154) & Contrast (6155)
if ($cfg.brightness -ne 0) {
    try {
        $pb = $item.Properties | Where-Object { $_.PropertyID -eq 6154 } | Select-Object -First 1
        if ($pb) {
            $val = [int]$cfg.brightness
            if ($pb.SubType -eq 1) {
                $val = [math]::Max([int]$pb.SubTypeMin, [math]::Min([int]$pb.SubTypeMax, $val))
            }
            $pb.Value = $val
        }
    } catch {}
}
if ($cfg.contrast -ne 0) {
    try {
        $pc = $item.Properties | Where-Object { $_.PropertyID -eq 6155 } | Select-Object -First 1
        if ($pc) {
            $val = [int]$cfg.contrast
            if ($pc.SubType -eq 1) {
                $val = [math]::Max([int]$pc.SubTypeMin, [math]::Min([int]$pc.SubTypeMax, $val))
            }
            $pc.Value = $val
        }
    } catch {}
}

# 5. Transfer BMP and save to temp path
$img = $item.Transfer('{B96B3CAB-0728-11D3-9D7B-0000F81EF32E}')
if (Test-Path $cfg.out_path) { Remove-Item $cfg.out_path -Force }
$img.SaveFile($cfg.out_path)
Write-Output 'OK'
"#;

    run_powershell_with_json(script, &input)?;
    if !temp_bmp.exists() {
        return Err("WIA scan finished without producing an output image file.".to_string());
    }
    Ok(temp_bmp)
}

async fn scan_via_escl(base_url: &str, req: &ScanRequest) -> Result<PathBuf, String> {
    let client = reqwest::Client::builder()
        .danger_accept_invalid_certs(true)
        .timeout(Duration::from_secs(60))
        .build()
        .map_err(|e| format!("HTTP client error: {e}"))?;

    let color_mode = match req.color_mode.as_str() {
        "Grayscale" => "Grayscale8",
        "Black & White" => "BlackAndWhite1",
        _ => "RGB24",
    };
    let input_source = if req.source == "adf" { "Feeder" } else { "Platen" };

    // eSCL uses 1/300th inch units
    let (w_300, h_300) = match req.page_size.as_str() {
        "a4" => (2480, 3508),
        "letter" => (2550, 3300),
        "4x6" => (1200, 1800),
        "5x7" => (1500, 2100),
        _ => (2550, 3508),
    };

    let xml_body = format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<scan:ScanSettings xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm" xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03">
  <pwg:Version>2.6</pwg:Version>
  <pwg:ScanRegions>
    <pwg:ScanRegion>
      <pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>
      <pwg:XOffset>0</pwg:XOffset>
      <pwg:YOffset>0</pwg:YOffset>
      <pwg:Width>{w_300}</pwg:Width>
      <pwg:Height>{h_300}</pwg:Height>
    </pwg:ScanRegion>
  </pwg:ScanRegions>
  <pwg:InputSource>{input_source}</pwg:InputSource>
  <scan:ColorMode>{color_mode}</scan:ColorMode>
  <scan:XResolution>{dpi}</scan:XResolution>
  <scan:YResolution>{dpi}</scan:YResolution>
  <pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat>
</scan:ScanSettings>"#,
        dpi = req.dpi.max(75)
    );

    let jobs_url = format!("{}/eSCL/ScanJobs", base_url.trim_end_matches('/'));
    let post_resp = client
        .post(&jobs_url)
        .header("Content-Type", "text/xml")
        .body(xml_body)
        .send()
        .await
        .map_err(|e| format!("Failed creating eSCL scan job: {e}"))?;

    if !post_resp.status().is_success() {
        return Err(format!(
            "eSCL ScanJobs returned HTTP {}",
            post_resp.status()
        ));
    }

    let loc = post_resp
        .headers()
        .get("Location")
        .and_then(|v| v.to_str().ok())
        .ok_or_else(|| "eSCL server did not return a Location header".to_string())?
        .to_string();

    let next_doc_url = if loc.starts_with("http://") || loc.starts_with("https://") {
        format!("{}/NextDocument", loc.trim_end_matches('/'))
    } else {
        format!(
            "{}/{}/NextDocument",
            base_url.trim_end_matches('/'),
            loc.trim_start_matches('/').trim_end_matches('/')
        )
    };

    let doc_resp = client
        .get(&next_doc_url)
        .send()
        .await
        .map_err(|e| format!("Failed downloading eSCL document: {e}"))?;

    if !doc_resp.status().is_success() {
        return Err(format!("eSCL NextDocument returned HTTP {}", doc_resp.status()));
    }

    let bytes = doc_resp
        .bytes()
        .await
        .map_err(|e| format!("Failed reading eSCL image bytes: {e}"))?;

    let tmp = std::env::temp_dir().join(format!("alprint_escl_{}.jpg", Uuid::new_v4()));
    fs::write(&tmp, &bytes).map_err(|e| format!("Failed writing temp eSCL image: {e}"))?;
    Ok(tmp)
}

fn persist_page_to_session(
    dir: &Path,
    session_id: &str,
    original_img: &DynamicImage,
    edits: PageEdits,
    dpi: u32,
    source_label: &str,
    suggested_crop: Option<CropRect>,
) -> Result<ScanPage, String> {
    let page_id = Uuid::new_v4().to_string();
    let original_path = dir.join(format!("{page_id}_orig.png"));
    let edited_path = dir.join(format!("{page_id}_edit.png"));

    image_ops::save_png(original_img, &original_path)?;
    let rendered = image_ops::apply_edits(original_img, &edits);
    image_ops::save_png(&rendered, &edited_path)?;
    let (w, h) = rendered.dimensions();

    Ok(ScanPage {
        id: page_id,
        session_id: session_id.to_string(),
        original_path: original_path.to_string_lossy().to_string(),
        edited_path: edited_path.to_string_lossy().to_string(),
        width: w,
        height: h,
        dpi,
        source_label: source_label.to_string(),
        edits,
        suggested_crop,
    })
}

pub fn import_image_files(session_id: &str, paths: &[String]) -> Result<Vec<ScanPage>, String> {
    let dir = session_dir(session_id)?;
    let mut pages = Vec::new();

    for p in paths {
        let img = image::open(p).map_err(|e| format!("Failed to open image {p}: {e}"))?;
        let fname = Path::new(p)
            .file_name()
            .and_then(|s| s.to_str())
            .unwrap_or("Imported");
        let page = persist_page_to_session(
            &dir,
            session_id,
            &img,
            PageEdits {
                filter: "none".to_string(),
                ..Default::default()
            },
            200,
            fname,
            None,
        )?;
        pages.push(page);
    }

    Ok(pages)
}

pub fn save_base64_page(
    session_id: &str,
    data_url: &str,
    source_label: &str,
    dpi: u32,
) -> Result<ScanPage, String> {
    let raw_b64 = data_url
        .split_once(",")
        .map(|(_, b)| b)
        .unwrap_or(data_url);
    let bytes = BASE64
        .decode(raw_b64.trim())
        .map_err(|e| format!("Invalid base64 image data: {e}"))?;
    let img = image::load_from_memory(&bytes)
        .map_err(|e| format!("Failed decoding rasterized image: {e}"))?;
    let dir = session_dir(session_id)?;
    persist_page_to_session(
        &dir,
        session_id,
        &img,
        PageEdits {
            filter: "none".to_string(),
            ..Default::default()
        },
        dpi,
        source_label,
        None,
    )
}

pub fn update_page_edits(mut page: ScanPage, new_edits: PageEdits) -> Result<ScanPage, String> {
    let orig = image::open(&page.original_path)
        .map_err(|e| format!("Failed loading original page {}: {e}", page.original_path))?;
    let rendered = image_ops::apply_edits(&orig, &new_edits);
    let edited_path = PathBuf::from(&page.edited_path);
    image_ops::save_png(&rendered, &edited_path)?;
    let (w, h) = rendered.dimensions();
    page.width = w;
    page.height = h;
    page.edits = new_edits;
    Ok(page)
}

pub fn overwrite_page_image(mut page: ScanPage, data_url: &str) -> Result<ScanPage, String> {
    let raw_b64 = data_url
        .split_once(",")
        .map(|(_, b)| b)
        .unwrap_or(data_url);
    let bytes = BASE64
        .decode(raw_b64.trim())
        .map_err(|e| format!("Invalid base64 image data: {e}"))?;
    let img = image::load_from_memory(&bytes)
        .map_err(|e| format!("Failed decoding annotated image: {e}"))?;
    image_ops::save_png(&img, Path::new(&page.original_path))?;
    image_ops::save_png(&img, Path::new(&page.edited_path))?;
    let (w, h) = img.dimensions();
    page.width = w;
    page.height = h;
    page.edits = PageEdits {
        filter: "none".to_string(),
        ..Default::default()
    };
    Ok(page)
}

pub fn stitch_id_card_pages(
    session_id: &str,
    front_path: &str,
    back_path: &str,
) -> Result<ScanPage, String> {
    let front = image::open(front_path).map_err(|e| format!("Failed opening front ID: {e}"))?;
    let back = image::open(back_path).map_err(|e| format!("Failed opening back ID: {e}"))?;
    let stitched = image_ops::stitch_id_card(&front, &back);
    let dir = session_dir(session_id)?;
    persist_page_to_session(
        &dir,
        session_id,
        &stitched,
        PageEdits {
            filter: "none".to_string(),
            ..Default::default()
        },
        300,
        "ID Card (Front + Back)",
        None,
    )
}
