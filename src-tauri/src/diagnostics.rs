use crate::models::DiagnosticReport;
use crate::ps_bridge::{run_powershell, run_powershell_with_json};
use serde::Serialize;

#[derive(Serialize)]
struct PrinterTarget {
    printer_name: Option<String>,
}

pub fn run_diagnostics(printer_name: Option<String>) -> Result<DiagnosticReport, String> {
    let input = PrinterTarget { printer_name };
    let script = r#"
$cfg = [System.IO.File]::ReadAllText($InputJsonPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$checks = @()
$queueJobs = @()
$ipAddr = $null

# 1. Print Spooler Service
try {
    $sp = Get-Service -Name Spooler
    $st = if ($sp.Status -eq 'Running') { 'pass' } else { 'fail' }
    $checks += [PSCustomObject]@{
        id = 'spooler'
        title = 'Windows Print Spooler Service'
        status = $st
        detail = "Service status: $($sp.Status) (StartType: $($sp.StartType))"
        action_hint = if ($st -ne 'pass') { 'Restart Print Spooler' } else { $null }
    }
} catch {
    $checks += [PSCustomObject]@{
        id = 'spooler'
        title = 'Windows Print Spooler Service'
        status = 'fail'
        detail = "Could not query Spooler service: $($_.Exception.Message)"
        action_hint = 'Restart Print Spooler'
    }
}

# 2. Windows Image Acquisition (WIA) Service
try {
    $wiaSvc = Get-Service -Name stisvc
    $st = if ($wiaSvc.Status -eq 'Running') { 'pass' } else { 'warn' }
    $checks += [PSCustomObject]@{
        id = 'stisvc'
        title = 'Windows Image Acquisition (WIA) Scanner Service'
        status = $st
        detail = "Service status: $($wiaSvc.Status) (StartType: $($wiaSvc.StartType))"
        action_hint = if ($st -ne 'pass') { 'Restart Print Spooler & WIA' } else { $null }
    }
} catch {
    $checks += [PSCustomObject]@{
        id = 'stisvc'
        title = 'Windows Image Acquisition (WIA) Scanner Service'
        status = 'warn'
        detail = "Could not query stisvc: $($_.Exception.Message)"
        action_hint = $null
    }
}

# 3. Target Printer Status & Queue
$targetName = $cfg.printer_name
if (-not $targetName) {
    $first = Get-Printer | Select-Object -First 1
    if ($first) { $targetName = $first.Name }
}

if ($targetName) {
    try {
        $pr = Get-Printer -Name $targetName -ErrorAction Stop
        $cim = Get-CimInstance Win32_Printer -Filter ("Name='" + ($pr.Name -replace "'", "''") + "'") -ErrorAction SilentlyContinue | Select-Object -First 1
        $workOffline = [bool]($cim -and $cim.WorkOffline)
        $rawStatus = [string]$pr.PrinterStatus
        $pStatus = if ($workOffline) { 'Offline' } else { $rawStatus }
        $isHealthy = (-not $workOffline) -and ($rawStatus -eq 'Normal' -or $rawStatus -eq 'Printing' -or $rawStatus -eq 'Warmup')
        $checks += [PSCustomObject]@{
            id = 'printer_status'
            title = "Printer Status: $($pr.Name)"
            status = if ($isHealthy) { 'pass' } elseif ($workOffline) { 'fail' } else { 'warn' }
            detail = "Status: $pStatus$(if ($workOffline) { ' (Disconnected / WorkOffline)' }) | Driver: $($pr.DriverName) | Port: $($pr.PortName)"
            action_hint = if (-not $isHealthy) { 'Check USB/Wi-Fi connection and power, or switch to the Wi-Fi printer queue' } else { $null }
        }

        try {
            $pt = Get-PrinterPort -Name $pr.PortName -ErrorAction SilentlyContinue
            if ($pt -and $pt.PrinterHostAddress) { $ipAddr = [string]$pt.PrinterHostAddress }
            elseif ($pr.PortName -match '(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})') { $ipAddr = $Matches[1] }
            if (-not $ipAddr -and $pr.PortName -like 'WSD*') {
                Get-ChildItem 'HKLM:\SYSTEM\CurrentControlSet\Enum\SWD\DAFWSDProvider' -ErrorAction SilentlyContinue | ForEach-Object {
                    if ($ipAddr) { return }
                    $fn = (Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue).FriendlyName
                    if ([string]$fn -eq [string]$pr.Name) {
                        $inst = "SWD\DAFWSDProvider\$($_.PSChildName)"
                        $prop = Get-PnpDeviceProperty -InstanceId $inst -KeyName '{656A3BB3-ECC0-43FD-8477-4AE0404A96CD} 4099' -ErrorAction SilentlyContinue
                        if ($prop -and ($prop.Data -join ' ') -match '(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})') {
                            $ipAddr = $Matches[1]
                        }
                    }
                }
            }
        } catch {}

        try {
            $jobs = @(Get-PrintJob -PrinterName $pr.Name -ErrorAction SilentlyContinue)
            foreach ($j in $jobs) {
                $queueJobs += [PSCustomObject]@{
                    id = [uint32]$j.ID
                    document_name = [string]$j.DocumentName
                    user_name = [string]$j.UserName
                    status = [string]$j.JobStatus
                    total_pages = [uint32]$j.TotalPages
                    size_bytes = [uint64]$j.Size
                }
            }
            $qStatus = if ($jobs.Count -eq 0) { 'pass' } elseif ($jobs | Where-Object { $_.JobStatus -match 'Error|Blocked|Offline' }) { 'fail' } else { 'warn' }
            $checks += [PSCustomObject]@{
                id = 'print_queue'
                title = 'Print Queue Check'
                status = $qStatus
                detail = if ($jobs.Count -eq 0) { 'No pending or stuck jobs in queue.' } else { "$($jobs.Count) job(s) currently in queue." }
                action_hint = if ($jobs.Count -gt 0) { 'Clear Print Queue' } else { $null }
            }
        } catch {}
    } catch {
        $checks += [PSCustomObject]@{
            id = 'printer_status'
            title = 'Target Printer Status'
            status = 'fail'
            detail = "Printer '$targetName' not found: $($_.Exception.Message)"
            action_hint = 'Refresh Devices'
        }
    }
} else {
    $checks += [PSCustomObject]@{
        id = 'printer_status'
        title = 'Installed Printers'
        status = 'warn'
        detail = 'No printers found in Windows.'
        action_hint = 'Connect printer via USB or Wi-Fi'
    }
}

# 4. WIA Scanner Detection
try {
    $dm = New-Object -ComObject WIA.DeviceManager
    $scannerNames = @()
    foreach ($d in $dm.DeviceInfos) {
        if ($d.Type -eq 1) {
            try { $scannerNames += [string]$d.Properties.Item('Name').Value } catch { $scannerNames += [string]$d.DeviceID }
        }
    }
    $checks += [PSCustomObject]@{
        id = 'wia_scanners'
        title = 'WIA Hardware Scanner Detection'
        status = if ($scannerNames.Count -gt 0) { 'pass' } else { 'warn' }
        detail = if ($scannerNames.Count -gt 0) { "Detected $($scannerNames.Count) scanner(s): $($scannerNames -join ', ')" } else { 'No WIA scanner detected right now. If networked, try adding an eSCL Wi-Fi scanner.' }
        action_hint = if ($scannerNames.Count -eq 0) { 'Check USB cable or add Wi-Fi eSCL scanner' } else { $null }
    }
} catch {
    $checks += [PSCustomObject]@{
        id = 'wia_scanners'
        title = 'WIA Hardware Scanner Detection'
        status = 'fail'
        detail = "WIA error: $($_.Exception.Message)"
        action_hint = 'Restart Print Spooler & WIA'
    }
}

# 5. Network Reachability (when IP exists)
if ($ipAddr) {
    $pingOk = Test-Connection -ComputerName $ipAddr -Count 1 -Quiet -ErrorAction SilentlyContinue
    $checks += [PSCustomObject]@{
        id = 'network_ping'
        title = "Network Reachability ($ipAddr)"
        status = if ($pingOk) { 'pass' } else { 'fail' }
        detail = if ($pingOk) { "Printer responded to ICMP ping at $ipAddr." } else { "No ping response from $ipAddr. Check Wi-Fi or router." }
        action_hint = if ($pingOk) { 'Open Embedded Web Server' } else { 'Verify printer Wi-Fi connection' }
    }
}

$report = [PSCustomObject]@{
    printer_name = $targetName
    checked_at = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    checks = $checks
    queue_jobs = $queueJobs
    ip_address = $ipAddr
}
ConvertTo-Json -InputObject $report -Depth 5 -Compress
"#;

    let out = run_powershell_with_json(script, &input)?;
    serde_json::from_str(&out)
        .map_err(|e| format!("Failed parsing diagnostic report JSON: {e} (raw: {out})"))
}

pub fn clear_print_queue(printer_name: &str) -> Result<String, String> {
    let input = PrinterTarget {
        printer_name: Some(printer_name.to_string()),
    };
    let script = r#"
$cfg = [System.IO.File]::ReadAllText($InputJsonPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$jobs = @(Get-PrintJob -PrinterName $cfg.printer_name -ErrorAction SilentlyContinue)
$count = $jobs.Count
foreach ($j in $jobs) {
    Remove-PrintJob -InputObject $j -ErrorAction SilentlyContinue
}
Write-Output "Cleared $count job(s) from $($cfg.printer_name)."
"#;
    run_powershell_with_json(script, &input)
}

pub fn restart_spooler_elevated() -> Result<String, String> {
    let script = r#"
$cmd = 'Restart-Service -Name Spooler -Force; Start-Service -Name stisvc -ErrorAction SilentlyContinue; Restart-Service -Name stisvc -Force -ErrorAction SilentlyContinue'
Start-Process powershell.exe -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-Command',$cmd) -Verb RunAs -Wait
Write-Output 'Print Spooler and WIA services restarted.'
"#;
    run_powershell(script)
}

pub fn open_printer_dialog(printer_name: &str, mode: &str) -> Result<(), String> {
    let flag = if mode == "properties" { "/p" } else { "/e" };
    std::process::Command::new("rundll32.exe")
        .args([
            "printui.dll,PrintUIEntry",
            flag,
            "/n",
            printer_name,
        ])
        .spawn()
        .map_err(|e| format!("Failed launching printer dialog: {e}"))?;
    Ok(())
}

pub fn open_path_or_url(target: &str) -> Result<(), String> {
    std::process::Command::new("explorer.exe")
        .arg(target)
        .spawn()
        .map_err(|e| format!("Failed opening {target}: {e}"))?;
    Ok(())
}
