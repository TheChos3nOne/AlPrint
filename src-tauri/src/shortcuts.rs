use crate::models::{ActivityEntry, AppStateData, EsclScannerConfig, ShortcutConfig};
use chrono::Local;
use std::fs;
use std::path::PathBuf;
use uuid::Uuid;

fn state_file_path() -> PathBuf {
    let base = dirs::data_dir().unwrap_or_else(std::env::temp_dir);
    base.join("AlPrint").join("state.json")
}

fn legacy_state_file_path() -> PathBuf {
    let base = dirs::data_dir().unwrap_or_else(std::env::temp_dir);
    base.join("PrintDesk").join("state.json")
}

fn default_scans_folder() -> String {
    let docs = dirs::document_dir().unwrap_or_else(std::env::temp_dir);
    docs.join("Scans").to_string_lossy().to_string()
}

pub fn default_shortcuts() -> Vec<ShortcutConfig> {
    let folder = default_scans_folder();
    vec![
        ShortcutConfig {
            id: "builtin-searchable-pdf".to_string(),
            name: "Document to Searchable PDF".to_string(),
            description: "300 DPI Color, auto-crop, auto-enhance, offline OCR, save Searchable PDF".to_string(),
            color: "indigo".to_string(),
            preset: "document".to_string(),
            dpi: 300,
            color_mode: "Color".to_string(),
            detect_edges: true,
            auto_enhance: true,
            run_ocr: true,
            save_enabled: true,
            save_folder: folder.clone(),
            filename_template: "Doc_{date}_{smart_name}".to_string(),
            save_format: "searchable_pdf".to_string(),
            print_enabled: false,
            print_copies: 1,
        },
        ShortcutConfig {
            id: "builtin-receipt-split".to_string(),
            name: "Multi-Receipt Scanner".to_string(),
            description: "200 DPI Grayscale, split multiple receipts on glass, OCR & save PDF".to_string(),
            color: "emerald".to_string(),
            preset: "multi_item".to_string(),
            dpi: 200,
            color_mode: "Grayscale".to_string(),
            detect_edges: true,
            auto_enhance: true,
            run_ocr: true,
            save_enabled: true,
            save_folder: folder.clone(),
            filename_template: "Receipts_{date}_{time}".to_string(),
            save_format: "searchable_pdf".to_string(),
            print_enabled: false,
            print_copies: 1,
        },
        ShortcutConfig {
            id: "builtin-quick-copy".to_string(),
            name: "Quick Copy (Flatbed)".to_string(),
            description: "200 DPI Color scan from flatbed, auto-crop & print 1 copy immediately".to_string(),
            color: "amber".to_string(),
            preset: "document".to_string(),
            dpi: 200,
            color_mode: "Color".to_string(),
            detect_edges: true,
            auto_enhance: true,
            run_ocr: false,
            save_enabled: false,
            save_folder: folder.clone(),
            filename_template: "Copy_{date}_{time}".to_string(),
            save_format: "pdf".to_string(),
            print_enabled: true,
            print_copies: 1,
        },
        ShortcutConfig {
            id: "builtin-id-card".to_string(),
            name: "ID Card Copy & Save".to_string(),
            description: "Guided front + back ID capture stitched on one page & saved as PDF".to_string(),
            color: "sky".to_string(),
            preset: "id_card".to_string(),
            dpi: 300,
            color_mode: "Color".to_string(),
            detect_edges: true,
            auto_enhance: true,
            run_ocr: false,
            save_enabled: true,
            save_folder: folder,
            filename_template: "ID_Card_{date}_{time}".to_string(),
            save_format: "pdf".to_string(),
            print_enabled: false,
            print_copies: 1,
        },
    ]
}

pub fn load_state() -> AppStateData {
    let path = state_file_path();
    let legacy = legacy_state_file_path();
    let read_from = if path.exists() {
        Some(&path)
    } else if legacy.exists() {
        Some(&legacy)
    } else {
        None
    };

    if let Some(src) = read_from {
        if let Ok(raw) = fs::read_to_string(src) {
            if let Ok(mut parsed) = serde_json::from_str::<AppStateData>(&raw) {
                if parsed.shortcuts.is_empty() {
                    parsed.shortcuts = default_shortcuts();
                }
                if !path.exists() {
                    let _ = save_state(&parsed);
                }
                return parsed;
            }
        }
    }
    let state = AppStateData {
        shortcuts: default_shortcuts(),
        escl_scanners: Vec::new(),
        recent_activity: Vec::new(),
    };
    let _ = save_state(&state);
    state
}

pub fn save_state(state: &AppStateData) -> Result<(), String> {
    let path = state_file_path();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|e| format!("Failed to create AppData AlPrint dir: {e}"))?;
    }
    let json = serde_json::to_string_pretty(state)
        .map_err(|e| format!("Failed to serialize app state: {e}"))?;
    fs::write(&path, json).map_err(|e| format!("Failed to write state.json: {e}"))
}

pub fn upsert_shortcut(shortcut: ShortcutConfig) -> Result<Vec<ShortcutConfig>, String> {
    let mut state = load_state();
    if let Some(existing) = state.shortcuts.iter_mut().find(|s| s.id == shortcut.id) {
        *existing = shortcut;
    } else {
        state.shortcuts.push(shortcut);
    }
    save_state(&state)?;
    Ok(state.shortcuts)
}

pub fn delete_shortcut(id: &str) -> Result<Vec<ShortcutConfig>, String> {
    let mut state = load_state();
    state.shortcuts.retain(|s| s.id != id);
    save_state(&state)?;
    Ok(state.shortcuts)
}

pub fn add_escl_config(cfg: EsclScannerConfig) -> Result<(), String> {
    let mut state = load_state();
    state.escl_scanners.retain(|s| s.base_url != cfg.base_url);
    state.escl_scanners.push(cfg);
    save_state(&state)
}

pub fn remove_escl_config(id: &str) -> Result<(), String> {
    let mut state = load_state();
    state.escl_scanners.retain(|s| s.id != id);
    save_state(&state)
}

pub fn log_activity(
    kind: &str,
    title: &str,
    detail: &str,
    output_path: Option<String>,
) -> Result<Vec<ActivityEntry>, String> {
    let mut state = load_state();
    state.recent_activity.insert(
        0,
        ActivityEntry {
            id: Uuid::new_v4().to_string(),
            timestamp: Local::now().format("%Y-%m-%d %H:%M").to_string(),
            kind: kind.to_string(),
            title: title.to_string(),
            detail: detail.to_string(),
            output_path,
        },
    );
    state.recent_activity.truncate(20);
    save_state(&state)?;
    Ok(state.recent_activity)
}

pub fn clear_activity() -> Result<(), String> {
    let mut state = load_state();
    state.recent_activity.clear();
    save_state(&state)
}
