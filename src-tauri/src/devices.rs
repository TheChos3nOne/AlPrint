use crate::models::{EsclScannerConfig, PrinterInfo, PrinterNetworkStatus, ScannerInfo, SupplyLevel};
use crate::ps_bridge::{run_powershell, run_powershell_with_json};
use crate::shortcuts;
use quick_xml::events::Event;
use quick_xml::Reader;
use serde::Serialize;
use std::net::UdpSocket;
use std::time::Duration;
use uuid::Uuid;

pub fn list_printers() -> Result<Vec<PrinterInfo>, String> {
    let script = r#"
$printers = Get-Printer | Sort-Object Name
$ports = @{}
try {
    foreach ($pt in (Get-PrinterPort)) {
        $ports[$pt.Name] = $pt
    }
} catch {}

$cimMap = @{}
try {
    foreach ($cp in (Get-CimInstance Win32_Printer -ErrorAction SilentlyContinue)) {
        $cimMap[[string]$cp.Name] = $cp
    }
} catch {}

$wsdIps = @{}
try {
    Get-ChildItem 'HKLM:\SYSTEM\CurrentControlSet\Enum\SWD\DAFWSDProvider' -ErrorAction SilentlyContinue | ForEach-Object {
        $fn = (Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue).FriendlyName
        if ($fn -and -not $wsdIps.ContainsKey([string]$fn)) {
            $inst = "SWD\DAFWSDProvider\$($_.PSChildName)"
            $prop = Get-PnpDeviceProperty -InstanceId $inst -KeyName '{656A3BB3-ECC0-43FD-8477-4AE0404A96CD} 4099' -ErrorAction SilentlyContinue
            if ($prop -and ($prop.Data -join ' ') -match '(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})') {
                $wsdIps[[string]$fn] = $Matches[1]
            }
        }
    }
} catch {}

$defaultName = ''
try {
    $regVal = (Get-ItemProperty 'HKCU:\Software\Microsoft\Windows NT\CurrentVersion\Windows' -Name Device -ErrorAction SilentlyContinue).Device
    if ($regVal) {
        $defaultName = ($regVal -split ',')[0].Trim()
    }
} catch {}

$defaultSizes = @(
    [PSCustomObject]@{ name = 'A4'; width_mm = 210.0; height_mm = 297.0 },
    [PSCustomObject]@{ name = 'Letter'; width_mm = 215.9; height_mm = 279.4 },
    [PSCustomObject]@{ name = '4x6 in'; width_mm = 101.6; height_mm = 152.4 },
    [PSCustomObject]@{ name = '5x7 in'; width_mm = 127.0; height_mm = 177.8 },
    [PSCustomObject]@{ name = '8x10 in'; width_mm = 203.2; height_mm = 254.0 }
)

$result = @()
foreach ($p in $printers) {
    $ip = $null
    $isNetwork = $false
    $ptObj = $ports[$p.PortName]
    if ($ptObj -and $ptObj.PrinterHostAddress) {
        $ip = [string]$ptObj.PrinterHostAddress
        $isNetwork = $true
    } elseif ($p.PortName -match '(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})') {
        $ip = $Matches[1]
        $isNetwork = $true
    } elseif ($p.PortName -like 'WSD*' -or $p.PortName -like 'IP_*' -or $p.PortName -like 'CNBJNP*') {
        $isNetwork = $true
    }
    if (-not $ip -and $wsdIps.ContainsKey([string]$p.Name)) {
        $ip = $wsdIps[[string]$p.Name]
        $isNetwork = $true
    }

    $cim = $cimMap[[string]$p.Name]
    $workOffline = [bool]($cim -and $cim.WorkOffline)
    $rawStatus = [string]$p.PrinterStatus
    $isOffline = $workOffline -or ($rawStatus -match 'Offline|Error|NotAvailable')
    $finalStatus = if ($isOffline) { 'Offline' } else { $rawStatus }

    $result += [PSCustomObject]@{
        name = [string]$p.Name
        driver_name = [string]$p.DriverName
        port_name = [string]$p.PortName
        status = $finalStatus
        is_default = ($p.Name -eq $defaultName)
        is_network = $isNetwork
        ip_address = $ip
        supports_color = $true
        supports_duplex = $false
        max_copies = 99
        paper_sizes = $defaultSizes
        capabilities_loaded = $false
        is_offline = $isOffline
    }
}

$json = ConvertTo-Json -InputObject @($result) -Depth 5 -Compress
Write-Output $json
"#;

    let out = run_powershell(script)?;
    if out.is_empty() {
        return Ok(Vec::new());
    }
    let printers: Vec<PrinterInfo> = serde_json::from_str(&out)
        .map_err(|e| format!("Failed to parse printer list JSON: {e} (raw: {out})"))?;
    Ok(printers)
}

#[derive(Serialize)]
struct PrinterCapInput {
    printer_name: String,
}

pub fn get_printer_capabilities(printer_name: &str) -> Result<PrinterInfo, String> {
    let input = PrinterCapInput {
        printer_name: printer_name.to_string(),
    };
    let script = r#"
$cfg = [System.IO.File]::ReadAllText($InputJsonPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
Add-Type -AssemblyName System.Drawing
$p = Get-Printer -Name $cfg.printer_name -ErrorAction Stop

$cim = Get-CimInstance Win32_Printer -Filter ("Name='" + ($p.Name -replace "'", "''") + "'") -ErrorAction SilentlyContinue | Select-Object -First 1
$workOffline = [bool]($cim -and $cim.WorkOffline)
$rawStatus = [string]$p.PrinterStatus
$isOffline = $workOffline -or ($rawStatus -match 'Offline|Error|NotAvailable')
$finalStatus = if ($isOffline) { 'Offline' } else { $rawStatus }

$ip = $null
$isNetwork = $false
try {
    $pt = Get-PrinterPort -Name $p.PortName -ErrorAction SilentlyContinue
    if ($pt -and $pt.PrinterHostAddress) {
        $ip = [string]$pt.PrinterHostAddress
        $isNetwork = $true
    } elseif ($p.PortName -match '(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})') {
        $ip = $Matches[1]
        $isNetwork = $true
    } elseif ($p.PortName -like 'WSD*' -or $p.PortName -like 'IP_*' -or $p.PortName -like 'CNBJNP*') {
        $isNetwork = $true
    }
    if (-not $ip -and $isNetwork) {
        Get-ChildItem 'HKLM:\SYSTEM\CurrentControlSet\Enum\SWD\DAFWSDProvider' -ErrorAction SilentlyContinue | ForEach-Object {
            if ($ip) { return }
            $fn = (Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue).FriendlyName
            if ([string]$fn -eq [string]$p.Name) {
                $inst = "SWD\DAFWSDProvider\$($_.PSChildName)"
                $prop = Get-PnpDeviceProperty -InstanceId $inst -KeyName '{656A3BB3-ECC0-43FD-8477-4AE0404A96CD} 4099' -ErrorAction SilentlyContinue
                if ($prop -and ($prop.Data -join ' ') -match '(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})') {
                    $ip = $Matches[1]
                }
            }
        }
    }
} catch {}

$defaultName = ''
try {
    $regVal = (Get-ItemProperty 'HKCU:\Software\Microsoft\Windows NT\CurrentVersion\Windows' -Name Device -ErrorAction SilentlyContinue).Device
    if ($regVal) { $defaultName = ($regVal -split ',')[0].Trim() }
} catch {}

$supportsColor = $true
$supportsDuplex = $false
$maxCopies = 99
$paperSizes = @()

# Avoid triggering Windows "Connecting to printer" popups on offline printers or generic WSD IPP ghost queues
$skipQuery = $isOffline -or ($p.PortName -like 'WSD*' -and $p.DriverName -eq 'Microsoft IPP Class Driver')
if (-not $skipQuery) {
    try {
        $ps = New-Object System.Drawing.Printing.PrinterSettings
        $ps.PrinterName = $p.Name
        if ($ps.IsValid) {
            $supportsColor = [bool]$ps.SupportsColor
            $supportsDuplex = [bool]$ps.CanDuplex
            $maxCopies = [int]$ps.MaximumCopies
            if ($maxCopies -lt 1) { $maxCopies = 99 }
            $seen = @{}
            foreach ($sz in $ps.PaperSizes) {
                $pname = [string]$sz.PaperName
                if ([string]::IsNullOrWhiteSpace($pname) -or $seen.ContainsKey($pname)) { continue }
                $seen[$pname] = $true
                $paperSizes += [PSCustomObject]@{
                    name = $pname
                    width_mm = [math]::Round($sz.Width * 0.254, 1)
                    height_mm = [math]::Round($sz.Height * 0.254, 1)
                }
            }
        }
    } catch {}
}

if ($paperSizes.Count -eq 0) {
    $paperSizes = @(
        [PSCustomObject]@{ name = 'A4'; width_mm = 210.0; height_mm = 297.0 },
        [PSCustomObject]@{ name = 'Letter'; width_mm = 215.9; height_mm = 279.4 },
        [PSCustomObject]@{ name = '4x6 in'; width_mm = 101.6; height_mm = 152.4 },
        [PSCustomObject]@{ name = '5x7 in'; width_mm = 127.0; height_mm = 177.8 },
        [PSCustomObject]@{ name = '8x10 in'; width_mm = 203.2; height_mm = 254.0 }
    )
}

$obj = [PSCustomObject]@{
    name = [string]$p.Name
    driver_name = [string]$p.DriverName
    port_name = [string]$p.PortName
    status = $finalStatus
    is_default = ($p.Name -eq $defaultName)
    is_network = $isNetwork
    ip_address = $ip
    supports_color = $supportsColor
    supports_duplex = $supportsDuplex
    max_copies = $maxCopies
    paper_sizes = $paperSizes
    capabilities_loaded = $true
    is_offline = $isOffline
}
ConvertTo-Json -InputObject $obj -Depth 5 -Compress
"#;

    let out = run_powershell_with_json(script, &input)?;
    serde_json::from_str(&out)
        .map_err(|e| format!("Failed to parse printer capabilities JSON: {e} (raw: {out})"))
}

pub fn list_wia_scanners() -> Result<Vec<ScannerInfo>, String> {
    let script = r#"
$dm = New-Object -ComObject WIA.DeviceManager
$scanners = @()
foreach ($info in $dm.DeviceInfos) {
    if ($info.Type -ne 1) { continue }
    $id = [string]$info.DeviceID
    $name = 'WIA Scanner'
    try { $name = [string]$info.Properties.Item('Name').Value } catch {}

    $scanners += [PSCustomObject]@{
        id = $id
        name = $name
        backend = 'wia'
        endpoint = $null
        supported_dpis = @(75, 100, 150, 200, 300, 400, 600, 1200)
        color_modes = @('Color', 'Grayscale', 'Black & White')
        has_adf = $false
        max_width_inches = 8.5
        max_height_inches = 11.69
        capabilities_loaded = $false
    }
}
$json = ConvertTo-Json -InputObject @($scanners) -Depth 4 -Compress
Write-Output $json
"#;

    let out = run_powershell(script)?;
    if out.is_empty() {
        return Ok(Vec::new());
    }
    let scanners: Vec<ScannerInfo> = serde_json::from_str(&out)
        .map_err(|e| format!("Failed to parse WIA scanner JSON: {e} (raw: {out})"))?;
    Ok(scanners)
}

#[derive(Serialize)]
struct ScannerCapInput {
    scanner_id: String,
}

pub fn probe_wia_scanner_capabilities(scanner_id: &str) -> Result<ScannerInfo, String> {
    let input = ScannerCapInput {
        scanner_id: scanner_id.to_string(),
    };
    let script = r#"
$cfg = [System.IO.File]::ReadAllText($InputJsonPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$dm = New-Object -ComObject WIA.DeviceManager
$targetInfo = $null
foreach ($info in $dm.DeviceInfos) {
    if ($info.Type -ne 1) { continue }
    if ([string]$info.DeviceID -eq [string]$cfg.scanner_id) {
        $targetInfo = $info
        break
    }
}
if (-not $targetInfo) {
    throw "WIA scanner '$($cfg.scanner_id)' not found."
}

$id = [string]$targetInfo.DeviceID
$name = 'WIA Scanner'
try { $name = [string]$targetInfo.Properties.Item('Name').Value } catch {}

$dpis = @(100, 150, 200, 300, 600)
$modes = @('Color', 'Grayscale', 'Black & White')
$hasAdf = $false
$maxW = 8.5
$maxH = 11.69

$dev = $targetInfo.Connect()
foreach ($dp in $dev.Properties) {
    if ($dp.PropertyID -eq 3088) {
        $hasAdf = (([int]$dp.Value -band 1) -ne 0)
    } elseif ($dp.PropertyID -eq 3074 -and [double]$dp.Value -gt 0) {
        $maxW = [math]::Round([double]$dp.Value / 1000.0, 2)
    } elseif ($dp.PropertyID -eq 3075 -and [double]$dp.Value -gt 0) {
        $maxH = [math]::Round([double]$dp.Value / 1000.0, 2)
    }
}
if ($dev.Items.Count -ge 1) {
    $item = $dev.Items.Item(1)
    foreach ($p in $item.Properties) {
        if ($p.PropertyID -eq 6147 -and $p.SubType -eq 2) {
            $vals = @()
            foreach ($v in $p.SubTypeValues) { $vals += [int]$v }
            if ($vals.Count -gt 0) { $dpis = $vals | Sort-Object }
        } elseif ($p.PropertyID -eq 4104 -and $p.SubType -eq 2) {
            $m = @()
            $bpps = @()
            foreach ($v in $p.SubTypeValues) { $bpps += [int]$v }
            if ($bpps -contains 24) { $m += 'Color' }
            if ($bpps -contains 8) { $m += 'Grayscale' }
            if ($bpps -contains 1) { $m += 'Black & White' }
            if ($m.Count -gt 0) { $modes = $m }
        }
    }
}

$obj = [PSCustomObject]@{
    id = $id
    name = $name
    backend = 'wia'
    endpoint = $null
    supported_dpis = $dpis
    color_modes = $modes
    has_adf = $hasAdf
    max_width_inches = $maxW
    max_height_inches = $maxH
    capabilities_loaded = $true
}
ConvertTo-Json -InputObject $obj -Depth 4 -Compress
"#;

    let out = run_powershell_with_json(script, &input)?;
    serde_json::from_str(&out)
        .map_err(|e| format!("Failed parsing scanner capabilities JSON: {e} (raw: {out})"))
}

pub async fn probe_escl_scanner(base_url: &str) -> Result<ScannerInfo, String> {
    let normalized = normalize_base_url(base_url);
    let cap_url = format!("{normalized}/eSCL/ScannerCapabilities");
    let client = reqwest::Client::builder()
        .danger_accept_invalid_certs(true)
        .timeout(Duration::from_secs(4))
        .build()
        .map_err(|e| format!("HTTP client error: {e}"))?;

    let resp = client
        .get(&cap_url)
        .send()
        .await
        .map_err(|e| format!("Could not reach {cap_url}: {e}"))?;

    if !resp.status().is_success() {
        return Err(format!("eSCL endpoint returned HTTP {}", resp.status()));
    }

    let xml = resp
        .text()
        .await
        .map_err(|e| format!("Failed reading eSCL XML: {e}"))?;

    Ok(parse_escl_capabilities(&normalized, &xml))
}

fn normalize_base_url(raw: &str) -> String {
    let trimmed = raw.trim().trim_end_matches('/');
    let with_scheme = if trimmed.starts_with("http://") || trimmed.starts_with("https://") {
        trimmed.to_string()
    } else {
        format!("http://{trimmed}")
    };
    with_scheme
        .trim_end_matches("/eSCL/ScannerCapabilities")
        .trim_end_matches("/eSCL")
        .trim_end_matches('/')
        .to_string()
}

fn parse_escl_capabilities(base_url: &str, xml: &str) -> ScannerInfo {
    let mut reader = Reader::from_str(xml);
    reader.config_mut().trim_text(true);

    let mut current_tag = String::new();
    let mut make_and_model = String::new();
    let mut dpis: Vec<u32> = Vec::new();
    let mut has_color = false;
    let mut has_gray = false;
    let mut has_bw = false;
    let mut has_adf = false;

    loop {
        match reader.read_event() {
            Ok(Event::Start(e)) => {
                let name = String::from_utf8_lossy(e.local_name().as_ref()).to_string();
                if name == "Adf" {
                    has_adf = true;
                }
                current_tag = name;
            }
            Ok(Event::Text(e)) => {
                let txt = e.unescape().map(|s| s.to_string()).unwrap_or_default();
                match current_tag.as_str() {
                    "MakeAndModel" if make_and_model.is_empty() => make_and_model = txt,
                    "XResolution" => {
                        if let Ok(v) = txt.parse::<u32>() {
                            if !dpis.contains(&v) {
                                dpis.push(v);
                            }
                        }
                    }
                    "ColorMode" => match txt.as_str() {
                        "RGB24" | "RGB48" => has_color = true,
                        "Grayscale8" | "Grayscale16" => has_gray = true,
                        "BlackAndWhite1" => has_bw = true,
                        _ => {}
                    },
                    _ => {}
                }
            }
            Ok(Event::Eof) => break,
            Err(_) => break,
            _ => {}
        }
    }

    if dpis.is_empty() {
        dpis = vec![75, 150, 200, 300, 600];
    } else {
        dpis.sort_unstable();
    }

    let mut modes = Vec::new();
    if has_color || (!has_gray && !has_bw) {
        modes.push("Color".to_string());
    }
    if has_gray {
        modes.push("Grayscale".to_string());
    }
    if has_bw {
        modes.push("Black & White".to_string());
    }

    let name = if make_and_model.is_empty() {
        format!("Network eSCL Scanner ({base_url})")
    } else {
        format!("{make_and_model} (Wi-Fi eSCL)")
    };

    ScannerInfo {
        id: format!("escl::{}", base_url),
        name,
        backend: "escl".to_string(),
        endpoint: Some(base_url.to_string()),
        supported_dpis: dpis,
        color_modes: modes,
        has_adf,
        max_width_inches: 8.5,
        max_height_inches: 11.69,
        capabilities_loaded: true,
    }
}

pub async fn list_all_scanners() -> Result<Vec<ScannerInfo>, String> {
    let mut all = list_wia_scanners().unwrap_or_default();
    let state = shortcuts::load_state();

    for cfg in state.escl_scanners {
        match probe_escl_scanner(&cfg.base_url).await {
            Ok(mut info) => {
                info.id = cfg.id.clone();
                if !cfg.name.is_empty() {
                    info.name = cfg.name.clone();
                }
                all.push(info);
            }
            Err(_) => {
                all.push(ScannerInfo {
                    id: cfg.id,
                    name: format!("{} (Offline)", cfg.name),
                    backend: "escl".to_string(),
                    endpoint: Some(cfg.base_url),
                    supported_dpis: vec![150, 200, 300, 600],
                    color_modes: vec!["Color".into(), "Grayscale".into(), "Black & White".into()],
                    has_adf: false,
                    max_width_inches: 8.5,
                    max_height_inches: 11.69,
                    capabilities_loaded: false,
                });
            }
        }
    }

    Ok(all)
}

pub async fn add_escl_scanner(base_url: &str, custom_name: Option<String>) -> Result<ScannerInfo, String> {
    let mut info = probe_escl_scanner(base_url).await?;
    let id = format!("escl-{}", Uuid::new_v4());
    if let Some(n) = custom_name {
        if !n.trim().is_empty() {
            info.name = n.trim().to_string();
        }
    }
    info.id = id.clone();

    shortcuts::add_escl_config(EsclScannerConfig {
        id,
        name: info.name.clone(),
        base_url: info.endpoint.clone().unwrap_or_else(|| normalize_base_url(base_url)),
    })?;

    Ok(info)
}

// --- Pure-Rust SNMP v1/v2c Printer-MIB Supply Level Query ---

pub async fn probe_network_printer_status(ip: &str) -> Result<PrinterNetworkStatus, String> {
    let clean_ip = ip.trim().to_string();
    let ews_url = format!("http://{clean_ip}");

    let client = reqwest::Client::builder()
        .danger_accept_invalid_certs(true)
        .timeout(Duration::from_secs(2))
        .build()
        .ok();

    let ews_reachable = if let Some(c) = client {
        c.get(&ews_url).send().await.is_ok()
    } else {
        false
    };

    let ip_clone = clean_ip.clone();
    let supplies = tokio::task::spawn_blocking(move || query_printer_mib_supplies(&ip_clone))
        .await
        .unwrap_or_default();

    let snmp_reachable = !supplies.is_empty();

    Ok(PrinterNetworkStatus {
        ip_address: clean_ip,
        ews_reachable,
        ews_url,
        snmp_reachable,
        supplies,
    })
}

fn query_printer_mib_supplies(ip: &str) -> Vec<SupplyLevel> {
    let target = format!("{ip}:161");
    let socket = match UdpSocket::bind("0.0.0.0:0") {
        Ok(s) => s,
        Err(_) => return Vec::new(),
    };
    let _ = socket.set_read_timeout(Some(Duration::from_millis(750)));
    let _ = socket.set_write_timeout(Some(Duration::from_millis(750)));

    let mut supplies = Vec::new();
    for idx in 1u32..=6 {
        let desc_oid = [1, 3, 6, 1, 2, 1, 43, 11, 1, 1, 6, 1, idx];
        let max_oid = [1, 3, 6, 1, 2, 1, 43, 11, 1, 1, 8, 1, idx];
        let lvl_oid = [1, 3, 6, 1, 2, 1, 43, 11, 1, 1, 9, 1, idx];

        let desc_raw = match snmp_get(&socket, &target, &desc_oid, 100 + idx * 3) {
            Some(SnmpVal::Str(s)) if !s.trim().is_empty() => s.trim().to_string(),
            _ => break,
        };
        let max_cap = match snmp_get(&socket, &target, &max_oid, 101 + idx * 3) {
            Some(SnmpVal::Int(v)) => v,
            _ => -1,
        };
        let raw_lvl = match snmp_get(&socket, &target, &lvl_oid, 102 + idx * 3) {
            Some(SnmpVal::Int(v)) => v,
            _ => -1,
        };

        let level_percent = if max_cap > 0 && raw_lvl >= 0 {
            Some(((raw_lvl as f64 / max_cap as f64) * 100.0).round().clamp(0.0, 100.0) as i32)
        } else {
            None
        };

        // Clean up HP Smart Tank firmware strings like "tri-color ink cartridge HP unknown"
        let desc = clean_supply_description(&desc_raw, max_cap);
        let color = infer_supply_color(&desc_raw);
        supplies.push(SupplyLevel {
            name: desc,
            color,
            level_percent,
            raw_level: raw_lvl,
            max_capacity: max_cap,
        });
    }
    supplies
}

fn clean_supply_description(raw: &str, max_cap: i32) -> String {
    let low = raw.to_ascii_lowercase();
    if low.contains("unknown") && max_cap <= 0 {
        if low.contains("tri-color") || low.contains("color") {
            return "Tri-Color Ink Tank (C/M/Y)".to_string();
        } else if low.contains("black") {
            return "Black Ink Tank (K)".to_string();
        }
    }
    raw.replace("HP unknown", "")
        .replace("unknown", "")
        .trim()
        .to_string()
}

fn infer_supply_color(name: &str) -> String {
    let low = name.to_ascii_lowercase();
    if low.contains("cyan") {
        "#06b6d4".to_string()
    } else if low.contains("magenta") {
        "#ec4899".to_string()
    } else if low.contains("yellow") {
        "#eab308".to_string()
    } else if low.contains("black") || low.contains("bk") {
        "#1e293b".to_string()
    } else {
        "#6366f1".to_string()
    }
}

enum SnmpVal {
    Str(String),
    Int(i32),
}

fn encode_oid(oid: &[u32]) -> Vec<u8> {
    let mut body = Vec::new();
    if oid.len() >= 2 {
        body.push((oid[0] * 40 + oid[1]) as u8);
        for &arc in &oid[2..] {
            if arc < 128 {
                body.push(arc as u8);
            } else {
                let mut stack = Vec::new();
                let mut v = arc;
                stack.push((v & 0x7f) as u8);
                v >>= 7;
                while v > 0 {
                    stack.push(((v & 0x7f) as u8) | 0x80);
                    v >>= 7;
                }
                stack.reverse();
                body.extend(stack);
            }
        }
    }
    wrap_tlv(0x06, &body)
}

fn wrap_tlv(tag: u8, val: &[u8]) -> Vec<u8> {
    let mut out = vec![tag];
    let len = val.len();
    if len < 128 {
        out.push(len as u8);
    } else if len < 256 {
        out.push(0x81);
        out.push(len as u8);
    } else {
        out.push(0x82);
        out.push((len >> 8) as u8);
        out.push((len & 0xff) as u8);
    }
    out.extend_from_slice(val);
    out
}

fn encode_int(val: i32) -> Vec<u8> {
    if (0..128).contains(&val) {
        wrap_tlv(0x02, &[val as u8])
    } else {
        let bytes = val.to_be_bytes();
        let mut start = 0;
        while start < 3 && bytes[start] == 0 && (bytes[start + 1] & 0x80) == 0 {
            start += 1;
        }
        wrap_tlv(0x02, &bytes[start..])
    }
}

fn snmp_get(socket: &UdpSocket, target: &str, oid: &[u32], req_id: u32) -> Option<SnmpVal> {
    let mut varbind = encode_oid(oid);
    varbind.extend_from_slice(&[0x05, 0x00]); // NULL
    let varbind_seq = wrap_tlv(0x30, &varbind);
    let varbind_list = wrap_tlv(0x30, &varbind_seq);

    let mut pdu_body = encode_int(req_id as i32);
    pdu_body.extend(encode_int(0)); // error-status
    pdu_body.extend(encode_int(0)); // error-index
    pdu_body.extend(varbind_list);
    let pdu = wrap_tlv(0xA0, &pdu_body); // GetRequest-PDU

    let mut msg_body = encode_int(0); // SNMPv1
    msg_body.extend(wrap_tlv(0x04, b"public"));
    msg_body.extend(pdu);
    let packet = wrap_tlv(0x30, &msg_body);

    socket.send_to(&packet, target).ok()?;
    let mut buf = [0u8; 2048];
    let (n, _) = socket.recv_from(&mut buf).ok()?;
    parse_snmp_response_val(&buf[..n])
}

fn read_tlv_len(data: &[u8], pos: &mut usize) -> Option<usize> {
    let b = *data.get(*pos)?;
    *pos += 1;
    if b < 128 {
        Some(b as usize)
    } else {
        let count = (b & 0x7f) as usize;
        if count == 0 || count > 4 || *pos + count > data.len() {
            return None;
        }
        let mut len = 0usize;
        for _ in 0..count {
            len = (len << 8) | (*data.get(*pos)? as usize);
            *pos += 1;
        }
        Some(len)
    }
}

fn parse_snmp_response_val(pkt: &[u8]) -> Option<SnmpVal> {
    let mut pos = 0usize;
    if *pkt.get(pos)? != 0x30 {
        return None;
    }
    pos += 1;
    let _ = read_tlv_len(pkt, &mut pos)?;

    // version + community
    for _ in 0..2 {
        pos += 1;
        let l = read_tlv_len(pkt, &mut pos)?;
        pos += l;
    }
    // GetResponse-PDU (0xA2)
    if *pkt.get(pos)? != 0xA2 {
        return None;
    }
    pos += 1;
    let _ = read_tlv_len(pkt, &mut pos)?;

    // req-id, error-status, error-index
    for idx in 0..3 {
        let tag = *pkt.get(pos)?;
        pos += 1;
        let l = read_tlv_len(pkt, &mut pos)?;
        if idx == 1 && tag == 0x02 && l == 1 && pkt[pos] != 0 {
            return None; // SNMP error-status != 0
        }
        pos += l;
    }

    // VarBindList -> VarBind
    for _ in 0..2 {
        if *pkt.get(pos)? != 0x30 {
            return None;
        }
        pos += 1;
        let _ = read_tlv_len(pkt, &mut pos)?;
    }

    // Skip OID
    if *pkt.get(pos)? != 0x06 {
        return None;
    }
    pos += 1;
    let oid_len = read_tlv_len(pkt, &mut pos)?;
    pos += oid_len;

    // Value
    let val_tag = *pkt.get(pos)?;
    pos += 1;
    let val_len = read_tlv_len(pkt, &mut pos)?;
    let slice = pkt.get(pos..pos + val_len)?;

    match val_tag {
        0x04 => Some(SnmpVal::Str(String::from_utf8_lossy(slice).to_string())),
        0x02 => {
            if slice.is_empty() || slice.len() > 4 {
                return None;
            }
            let sign = if (slice[0] & 0x80) != 0 { -1i32 } else { 0i32 };
            let mut acc = sign;
            for &b in slice {
                acc = (acc << 8) | (b as i32);
            }
            Some(SnmpVal::Int(acc))
        }
        _ => None,
    }
}
