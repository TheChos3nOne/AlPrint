use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PaperSizeInfo {
    pub name: String,
    pub width_mm: f64,
    pub height_mm: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PrinterInfo {
    pub name: String,
    pub driver_name: String,
    pub port_name: String,
    pub status: String,
    pub is_default: bool,
    pub is_network: bool,
    pub ip_address: Option<String>,
    pub supports_color: bool,
    pub supports_duplex: bool,
    pub max_copies: u32,
    pub paper_sizes: Vec<PaperSizeInfo>,
    #[serde(default)]
    pub capabilities_loaded: bool,
    #[serde(default)]
    pub is_offline: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScannerInfo {
    pub id: String,
    pub name: String,
    pub backend: String, // "wia" | "escl"
    pub endpoint: Option<String>,
    pub supported_dpis: Vec<u32>,
    pub color_modes: Vec<String>,
    pub has_adf: bool,
    pub max_width_inches: f64,
    pub max_height_inches: f64,
    #[serde(default)]
    pub capabilities_loaded: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EsclScannerConfig {
    pub id: String,
    pub name: String,
    pub base_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SupplyLevel {
    pub name: String,
    pub color: String,
    pub level_percent: Option<i32>,
    pub raw_level: i32,
    pub max_capacity: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PrinterNetworkStatus {
    pub ip_address: String,
    pub ews_reachable: bool,
    pub ews_url: String,
    pub snmp_reachable: bool,
    pub supplies: Vec<SupplyLevel>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CropRect {
    pub x: f64,
    pub y: f64,
    pub w: f64,
    pub h: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct PageEdits {
    pub crop: Option<CropRect>,
    pub rotate_deg: i32,      // 0, 90, 180, 270
    pub brightness: i32,      // -100..100
    pub contrast: i32,        // -100..100
    pub saturation: i32,      // -100..100
    pub filter: String,       // "none" | "auto_enhance" | "grayscale" | "bw_document"
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScanPage {
    pub id: String,
    pub session_id: String,
    pub original_path: String,
    pub edited_path: String,
    pub width: u32,
    pub height: u32,
    pub dpi: u32,
    pub source_label: String,
    pub edits: PageEdits,
    #[serde(default)]
    pub suggested_crop: Option<CropRect>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScanRequest {
    pub session_id: String,
    pub scanner_id: String,
    pub backend: String, // "wia" | "escl"
    pub endpoint: Option<String>,
    pub source: String,     // "flatbed" | "adf"
    pub page_size: String,  // "full" | "a4" | "letter" | "4x6" | "5x7"
    pub color_mode: String, // "Color" | "Grayscale" | "Black & White"
    pub dpi: u32,
    pub brightness: i32,
    pub contrast: i32,
    pub detect_edges: bool,
    pub auto_enhance: bool,
    pub preset: String, // "document" | "photo" | "id_card" | "multi_item" | "book_spread"
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OcrWord {
    pub text: String,
    pub x: f64, // normalized 0..1
    pub y: f64,
    pub w: f64,
    pub h: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OcrLine {
    pub text: String,
    pub words: Vec<OcrWord>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OcrResult {
    pub full_text: String,
    pub suggested_filename: String,
    pub width: u32,
    pub height: u32,
    pub language: String,
    pub lines: Vec<OcrLine>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PrintRequest {
    pub printer_name: String,
    pub page_paths: Vec<String>,
    pub copies: u32,
    pub landscape: bool,
    pub paper_size_name: String,
    pub color: bool,
    pub duplex: String,   // "simplex" | "long_edge" | "short_edge"
    pub borderless: bool,
    pub fit_mode: String, // "fit" | "fill"
    pub job_name: Option<String>,
    #[serde(default)]
    pub margin_mm: Option<f64>,
    #[serde(default)]
    pub scale_percent: Option<u32>,
    #[serde(default)]
    pub dpi: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiagnosticCheck {
    pub id: String,
    pub title: String,
    pub status: String, // "pass" | "warn" | "fail"
    pub detail: String,
    pub action_hint: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PrintQueueJob {
    pub id: u32,
    pub document_name: String,
    pub user_name: String,
    pub status: String,
    pub total_pages: u32,
    pub size_bytes: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiagnosticReport {
    pub printer_name: Option<String>,
    pub checked_at: String,
    pub checks: Vec<DiagnosticCheck>,
    pub queue_jobs: Vec<PrintQueueJob>,
    pub ip_address: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ShortcutConfig {
    pub id: String,
    pub name: String,
    pub description: String,
    pub color: String,
    pub preset: String,
    pub dpi: u32,
    pub color_mode: String,
    pub detect_edges: bool,
    pub auto_enhance: bool,
    pub run_ocr: bool,
    pub save_enabled: bool,
    pub save_folder: String,
    pub filename_template: String,
    pub save_format: String, // "searchable_pdf" | "pdf" | "jpg" | "png"
    pub print_enabled: bool,
    pub print_copies: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ActivityEntry {
    pub id: String,
    pub timestamp: String,
    pub kind: String, // "scan" | "print" | "shortcut" | "export"
    pub title: String,
    pub detail: String,
    pub output_path: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct AppStateData {
    pub shortcuts: Vec<ShortcutConfig>,
    pub escl_scanners: Vec<EsclScannerConfig>,
    pub recent_activity: Vec<ActivityEntry>,
}
