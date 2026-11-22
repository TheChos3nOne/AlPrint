mod devices;
mod diagnostics;
mod image_ops;
mod models;
mod ocr;
mod printer;
mod ps_bridge;
mod scanner;
mod shortcuts;

use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use models::{
    ActivityEntry, AppStateData, DiagnosticReport, OcrResult, PageEdits, PrintRequest,
    PrinterInfo, PrinterNetworkStatus, ScanPage, ScanRequest, ScannerInfo, ShortcutConfig,
};
use std::fs;
use std::path::PathBuf;

#[tauri::command]
async fn list_printers() -> Result<Vec<PrinterInfo>, String> {
    tokio::task::spawn_blocking(devices::list_printers)
        .await
        .map_err(|e| format!("Task error: {e}"))?
}

#[tauri::command]
async fn get_printer_capabilities(printer_name: String) -> Result<PrinterInfo, String> {
    tokio::task::spawn_blocking(move || devices::get_printer_capabilities(&printer_name))
        .await
        .map_err(|e| format!("Task error: {e}"))?
}

#[tauri::command]
async fn list_scanners() -> Result<Vec<ScannerInfo>, String> {
    devices::list_all_scanners().await
}

#[tauri::command]
async fn probe_wia_scanner_capabilities(scanner_id: String) -> Result<ScannerInfo, String> {
    tokio::task::spawn_blocking(move || devices::probe_wia_scanner_capabilities(&scanner_id))
        .await
        .map_err(|e| format!("Task error: {e}"))?
}

#[tauri::command]
async fn add_escl_scanner(base_url: String, custom_name: Option<String>) -> Result<ScannerInfo, String> {
    devices::add_escl_scanner(&base_url, custom_name).await
}

#[tauri::command]
fn remove_escl_scanner(id: String) -> Result<(), String> {
    shortcuts::remove_escl_config(&id)
}

#[tauri::command]
async fn probe_network_printer_status(ip: String) -> Result<PrinterNetworkStatus, String> {
    devices::probe_network_printer_status(&ip).await
}

#[tauri::command]
async fn scan_page(request: ScanRequest) -> Result<Vec<ScanPage>, String> {
    let pages = scanner::scan_page(request).await?;
    let _ = shortcuts::log_activity(
        "scan",
        &format!("Scanned {} page(s)", pages.len()),
        pages
            .first()
            .map(|p| format!("{} DPI · {}", p.dpi, p.source_label))
            .unwrap_or_default()
            .as_str(),
        pages.first().map(|p| p.edited_path.clone()),
    );
    Ok(pages)
}

#[tauri::command]
fn import_image_files(session_id: String, paths: Vec<String>) -> Result<Vec<ScanPage>, String> {
    scanner::import_image_files(&session_id, &paths)
}

#[tauri::command]
fn save_base64_page(
    session_id: String,
    data_url: String,
    source_label: String,
    dpi: u32,
) -> Result<ScanPage, String> {
    scanner::save_base64_page(&session_id, &data_url, &source_label, dpi)
}

#[tauri::command]
fn update_page_edits(page: ScanPage, edits: PageEdits) -> Result<ScanPage, String> {
    scanner::update_page_edits(page, edits)
}

#[tauri::command]
fn overwrite_page_image(page: ScanPage, data_url: String) -> Result<ScanPage, String> {
    scanner::overwrite_page_image(page, &data_url)
}

#[tauri::command]
fn stitch_id_card_pages(
    session_id: String,
    front_path: String,
    back_path: String,
) -> Result<ScanPage, String> {
    scanner::stitch_id_card_pages(&session_id, &front_path, &back_path)
}

#[tauri::command]
fn read_file_base64(path: String) -> Result<String, String> {
    let bytes = fs::read(&path).map_err(|e| format!("Failed reading {path}: {e}"))?;
    Ok(BASE64.encode(bytes))
}

#[tauri::command]
fn export_images(
    page_paths: Vec<String>,
    output_dir: String,
    format: String,
    compression: String,
    base_name: String,
) -> Result<Vec<String>, String> {
    let written = image_ops::export_images(&page_paths, &output_dir, &format, &compression, &base_name)?;
    let _ = shortcuts::log_activity(
        "export",
        &format!("Exported {} {} image(s)", written.len(), format.to_uppercase()),
        &output_dir,
        written.first().cloned(),
    );
    Ok(written)
}

#[tauri::command]
fn save_pdf_bytes(output_path: String, base64_pdf: String) -> Result<String, String> {
    let bytes = BASE64
        .decode(base64_pdf.trim())
        .map_err(|e| format!("Invalid PDF base64 payload: {e}"))?;
    let mut path = PathBuf::from(&output_path);
    if path.exists() {
        if let (Some(parent), Some(stem)) = (path.parent(), path.file_stem().and_then(|s| s.to_str())) {
            path = image_ops::unique_path(parent, stem, "pdf");
        }
    } else if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("Failed creating folder: {e}"))?;
    }
    fs::write(&path, bytes).map_err(|e| format!("Failed writing PDF: {e}"))?;
    let path_str = path.to_string_lossy().to_string();
    let _ = shortcuts::log_activity(
        "export",
        "Saved PDF",
        path.file_name()
            .and_then(|s| s.to_str())
            .unwrap_or("Document.pdf"),
        Some(path_str.clone()),
    );
    Ok(path_str)
}

#[tauri::command]
fn save_text_file(output_path: String, content: String) -> Result<String, String> {
    let path = PathBuf::from(&output_path);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("Failed creating folder: {e}"))?;
    }
    fs::write(&path, content.as_bytes()).map_err(|e| format!("Failed writing text file: {e}"))?;
    Ok(output_path)
}

#[tauri::command]
async fn run_ocr(image_path: String, language: Option<String>) -> Result<OcrResult, String> {
    tokio::task::spawn_blocking(move || ocr::run_ocr(&image_path, language))
        .await
        .map_err(|e| format!("Task error: {e}"))?
}

#[tauri::command]
async fn print_pages(request: PrintRequest) -> Result<String, String> {
    let printer_name = request.printer_name.clone();
    let count = request.page_paths.len();
    let res = tokio::task::spawn_blocking(move || printer::print_pages(request))
        .await
        .map_err(|e| format!("Task error: {e}"))??;
    let _ = shortcuts::log_activity(
        "print",
        &format!("Printed {} page(s)", count),
        &printer_name,
        None,
    );
    Ok(res)
}

#[tauri::command]
async fn print_test_page(printer_name: String) -> Result<String, String> {
    let pname = printer_name.clone();
    let res = tokio::task::spawn_blocking(move || printer::print_test_page(&pname))
        .await
        .map_err(|e| format!("Task error: {e}"))??;
    let _ = shortcuts::log_activity("print", "Printed Test Page", &printer_name, None);
    Ok(res)
}

#[tauri::command]
async fn run_diagnostics(printer_name: Option<String>) -> Result<DiagnosticReport, String> {
    tokio::task::spawn_blocking(move || diagnostics::run_diagnostics(printer_name))
        .await
        .map_err(|e| format!("Task error: {e}"))?
}

#[tauri::command]
async fn clear_print_queue(printer_name: String) -> Result<String, String> {
    tokio::task::spawn_blocking(move || diagnostics::clear_print_queue(&printer_name))
        .await
        .map_err(|e| format!("Task error: {e}"))?
}

#[tauri::command]
async fn restart_spooler_elevated() -> Result<String, String> {
    tokio::task::spawn_blocking(diagnostics::restart_spooler_elevated)
        .await
        .map_err(|e| format!("Task error: {e}"))?
}

#[tauri::command]
fn open_printer_dialog(printer_name: String, mode: String) -> Result<(), String> {
    diagnostics::open_printer_dialog(&printer_name, &mode)
}

#[tauri::command]
fn open_path_or_url(target: String) -> Result<(), String> {
    diagnostics::open_path_or_url(&target)
}

#[tauri::command]
fn get_app_state() -> AppStateData {
    shortcuts::load_state()
}

#[tauri::command]
fn save_shortcut(shortcut: ShortcutConfig) -> Result<Vec<ShortcutConfig>, String> {
    shortcuts::upsert_shortcut(shortcut)
}

#[tauri::command]
fn delete_shortcut(id: String) -> Result<Vec<ShortcutConfig>, String> {
    shortcuts::delete_shortcut(&id)
}

#[tauri::command]
fn log_activity(
    kind: String,
    title: String,
    detail: String,
    output_path: Option<String>,
) -> Result<Vec<ActivityEntry>, String> {
    shortcuts::log_activity(&kind, &title, &detail, output_path)
}

#[tauri::command]
fn clear_activity() -> Result<(), String> {
    shortcuts::clear_activity()
}

#[tauri::command]
fn get_default_scans_dir() -> String {
    dirs::document_dir()
        .unwrap_or_else(std::env::temp_dir)
        .join("Scans")
        .to_string_lossy()
        .to_string()
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_fs::init())
        .plugin(tauri_plugin_shell::init())
        .invoke_handler(tauri::generate_handler![
            list_printers,
            get_printer_capabilities,
            list_scanners,
            probe_wia_scanner_capabilities,
            add_escl_scanner,
            remove_escl_scanner,
            probe_network_printer_status,
            scan_page,
            import_image_files,
            save_base64_page,
            update_page_edits,
            overwrite_page_image,
            stitch_id_card_pages,
            read_file_base64,
            export_images,
            save_pdf_bytes,
            save_text_file,
            run_ocr,
            print_pages,
            print_test_page,
            run_diagnostics,
            clear_print_queue,
            restart_spooler_elevated,
            open_printer_dialog,
            open_path_or_url,
            get_app_state,
            save_shortcut,
            delete_shortcut,
            log_activity,
            clear_activity,
            get_default_scans_dir
        ])
        .run(tauri::generate_context!())
        .expect("error while running AlPrint Tauri application");
}
