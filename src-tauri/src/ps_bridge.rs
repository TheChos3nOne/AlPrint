use serde::Serialize;
use std::fs;
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use std::process::Command;
use uuid::Uuid;

const CREATE_NO_WINDOW: u32 = 0x08000000;

fn temp_file(ext: &str) -> PathBuf {
    std::env::temp_dir().join(format!("alprint_{}.{}", Uuid::new_v4(), ext))
}

/// Runs a PowerShell script hidden (no console window) passing structured JSON input via a temp file.
/// The PowerShell script receives `$InputJsonPath` as its first positional parameter (`param([string]$InputJsonPath)`).
pub fn run_powershell_with_json<T: Serialize>(script_body: &str, input: &T) -> Result<String, String> {
    let ps_path = temp_file("ps1");
    let json_path = temp_file("json");

    let json_str = serde_json::to_string(input)
        .map_err(|e| format!("Failed to serialize PowerShell input JSON: {e}"))?;
    fs::write(&json_path, json_str.as_bytes())
        .map_err(|e| format!("Failed to write PowerShell input JSON: {e}"))?;

    let wrapped = format!(
        "param([string]$InputJsonPath)\r\n\
         $ErrorActionPreference = 'Stop'\r\n\
         [Console]::OutputEncoding = [System.Text.Encoding]::UTF8\r\n\
         $OutputEncoding = [System.Text.Encoding]::UTF8\r\n\
         {}\r\n",
        script_body
    );

    fs::write(&ps_path, wrapped.as_bytes())
        .map_err(|e| format!("Failed to write temp PowerShell script: {e}"))?;

    let output = Command::new("powershell.exe")
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            &ps_path.to_string_lossy(),
            &json_path.to_string_lossy(),
        ])
        .creation_flags(CREATE_NO_WINDOW)
        .output();

    let _ = fs::remove_file(&ps_path);
    let _ = fs::remove_file(&json_path);

    let output = output.map_err(|e| format!("Failed to launch powershell.exe: {e}"))?;
    let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
    let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();

    if !output.status.success() {
        let msg = if stderr.is_empty() { stdout } else { stderr };
        return Err(format!("PowerShell error: {msg}"));
    }

    Ok(stdout)
}

/// Runs a PowerShell script hidden with no JSON input.
pub fn run_powershell(script_body: &str) -> Result<String, String> {
    run_powershell_with_json(script_body, &serde_json::json!({}))
}
