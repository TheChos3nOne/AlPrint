use crate::models::OcrResult;
use crate::ps_bridge::run_powershell_with_json;
use serde::Serialize;

#[derive(Serialize)]
struct OcrInput {
    image_path: String,
    language: Option<String>,
}

pub fn run_ocr(image_path: &str, language: Option<String>) -> Result<OcrResult, String> {
    let input = OcrInput {
        image_path: image_path.to_string(),
        language,
    };

    let script = r#"
$cfg = [System.IO.File]::ReadAllText($InputJsonPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
if (-not (Test-Path $cfg.image_path)) {
    throw "OCR image not found: $($cfg.image_path)"
}

Add-Type -AssemblyName System.Runtime.WindowsRuntime

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and
    $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
}) | Select-Object -First 1

function Await-WinRT($WinRtTask, $ResultType) {
    $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
    $netTask = $asTask.Invoke($null, @($WinRtTask))
    $netTask.Wait(-1) | Out-Null
    return $netTask.Result
}

$null = [Windows.Storage.StorageFile, Windows.Storage, ContentType=WindowsRuntime]
$null = [Windows.Storage.Streams.IRandomAccessStream, Windows.Storage.Streams, ContentType=WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType=WindowsRuntime]
$null = [Windows.Graphics.Imaging.SoftwareBitmap, Windows.Graphics.Imaging, ContentType=WindowsRuntime]
$null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType=WindowsRuntime]
$null = [Windows.Media.Ocr.OcrResult, Windows.Foundation, ContentType=WindowsRuntime]
$null = [Windows.Globalization.Language, Windows.Globalization, ContentType=WindowsRuntime]

$absPath = (Resolve-Path $cfg.image_path).Path
$file = Await-WinRT ([Windows.Storage.StorageFile]::GetFileFromPathAsync($absPath)) ([Windows.Storage.StorageFile])
$stream = Await-WinRT ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
$decoder = Await-WinRT ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
$bitmap = Await-WinRT ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])

$engine = $null
if ($cfg.language) {
    try {
        $lang = New-Object Windows.Globalization.Language($cfg.language)
        if ([Windows.Media.Ocr.OcrEngine]::IsLanguageSupported($lang)) {
            $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($lang)
        }
    } catch {}
}
if (-not $engine) {
    $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
}
if (-not $engine) {
    $avail = [Windows.Media.Ocr.OcrEngine]::AvailableRecognizerLanguages
    if ($avail.Count -gt 0) {
        $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($avail[0])
    }
}
if (-not $engine) {
    throw 'No Windows OCR language pack is available on this system.'
}

$res = Await-WinRT ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
$w = [double]$bitmap.PixelWidth
$h = [double]$bitmap.PixelHeight
if ($w -le 0) { $w = 1.0 }
if ($h -le 0) { $h = 1.0 }

$linesOut = @()
foreach ($ln in $res.Lines) {
    $wordsOut = @()
    foreach ($wd in $ln.Words) {
        $r = $wd.BoundingRect
        $wordsOut += [PSCustomObject]@{
            text = [string]$wd.Text
            x = [math]::Round($r.X / $w, 5)
            y = [math]::Round($r.Y / $h, 5)
            w = [math]::Round($r.Width / $w, 5)
            h = [math]::Round($r.Height / $h, 5)
        }
    }
    $linesOut += [PSCustomObject]@{
        text = [string]$ln.Text
        words = $wordsOut
    }
}

$stream.Dispose()
$bitmap.Dispose()

$payload = [PSCustomObject]@{
    full_text = [string]$res.Text
    suggested_filename = ''
    width = [int]$bitmap.PixelWidth
    height = [int]$bitmap.PixelHeight
    language = [string]$engine.RecognizerLanguage.LanguageTag
    lines = $linesOut
}
ConvertTo-Json -InputObject $payload -Depth 6 -Compress
"#;

    let out = run_powershell_with_json(script, &input)?;
    let mut ocr: OcrResult = serde_json::from_str(&out)
        .map_err(|e| format!("Failed parsing Windows OCR JSON: {e} (raw: {out})"))?;
    ocr.suggested_filename = suggest_filename(&ocr);
    Ok(ocr)
}

fn suggest_filename(ocr: &OcrResult) -> String {
    let mut parts: Vec<String> = Vec::new();
    for line in &ocr.lines {
        let trimmed = line.text.trim();
        if trimmed.len() < 3 {
            continue;
        }
        // Keep clean alphanumeric words for a short readable slug
        let slug: String = trimmed
            .chars()
            .map(|c| if c.is_alphanumeric() || c == ' ' || c == '-' { c } else { ' ' })
            .collect::<String>()
            .split_whitespace()
            .take(5)
            .collect::<Vec<_>>()
            .join("_");
        if slug.len() >= 3 {
            parts.push(slug);
            break;
        }
    }
    if parts.is_empty() {
        format!("Scan_{}", chrono::Local::now().format("%Y-%m-%d"))
    } else {
        let date = chrono::Local::now().format("%Y-%m-%d");
        format!("{}_{}", date, crate::image_ops::sanitize_filename(&parts[0]))
    }
}
