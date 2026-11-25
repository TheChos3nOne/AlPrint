export type TabId =
  | "dashboard"
  | "scan"
  | "print-docs"
  | "print-photos"
  | "shortcuts"
  | "diagnostics";

export interface PaperSizeInfo {
  name: string;
  width_mm: number;
  height_mm: number;
}

export interface PrinterInfo {
  name: string;
  driver_name: string;
  port_name: string;
  status: string;
  is_default: boolean;
  is_network: boolean;
  ip_address: string | null;
  supports_color: boolean;
  supports_duplex: boolean;
  max_copies: number;
  paper_sizes: PaperSizeInfo[];
  capabilities_loaded?: boolean;
  is_offline?: boolean;
}

export interface ScannerInfo {
  id: string;
  name: string;
  backend: "wia" | "escl";
  endpoint: string | null;
  supported_dpis: number[];
  color_modes: string[];
  has_adf: boolean;
  max_width_inches: number;
  max_height_inches: number;
  capabilities_loaded?: boolean;
}

export interface SupplyLevel {
  name: string;
  color: string;
  level_percent: number | null;
  raw_level: number;
  max_capacity: number;
}

export interface PrinterNetworkStatus {
  ip_address: string;
  ews_reachable: boolean;
  ews_url: string;
  snmp_reachable: boolean;
  supplies: SupplyLevel[];
}

export interface CropRect {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface PageEdits {
  crop: CropRect | null;
  rotate_deg: number;
  brightness: number;
  contrast: number;
  saturation: number;
  filter: "none" | "auto_enhance" | "grayscale" | "bw_document";
}

export interface ScanPage {
  id: string;
  session_id: string;
  original_path: string;
  edited_path: string;
  width: number;
  height: number;
  dpi: number;
  source_label: string;
  edits: PageEdits;
  suggested_crop?: CropRect | null;
}

export interface ScanRequest {
  session_id: string;
  scanner_id: string;
  backend: "wia" | "escl";
  endpoint: string | null;
  source: "flatbed" | "adf";
  page_size: "full" | "a4" | "letter" | "4x6" | "5x7";
  color_mode: "Color" | "Grayscale" | "Black & White";
  dpi: number;
  brightness: number;
  contrast: number;
  detect_edges: boolean;
  auto_enhance: boolean;
  preset: "document" | "photo" | "id_card" | "multi_item" | "book_spread";
}

export interface OcrWord {
  text: string;
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface OcrLine {
  text: string;
  words: OcrWord[];
}

export interface OcrResult {
  full_text: string;
  suggested_filename: string;
  width: number;
  height: number;
  language: string;
  lines: OcrLine[];
}

export interface PrintRequest {
  printer_name: string;
  page_paths: string[];
  copies: number;
  landscape: boolean;
  paper_size_name: string;
  color: boolean;
  duplex: "simplex" | "long_edge" | "short_edge";
  borderless: boolean;
  fit_mode: "fit" | "fill";
  job_name?: string;
  margin_mm?: number;
  scale_percent?: number;
}

export interface DiagnosticCheck {
  id: string;
  title: string;
  status: "pass" | "warn" | "fail";
  detail: string;
  action_hint: string | null;
}

export interface PrintQueueJob {
  id: number;
  document_name: string;
  user_name: string;
  status: string;
  total_pages: number;
  size_bytes: number;
}

export interface DiagnosticReport {
  printer_name: string | null;
  checked_at: string;
  checks: DiagnosticCheck[];
  queue_jobs: PrintQueueJob[];
  ip_address: string | null;
}

export interface ShortcutConfig {
  id: string;
  name: string;
  description: string;
  color: string;
  preset: "document" | "photo" | "id_card" | "multi_item" | "book_spread";
  dpi: number;
  color_mode: "Color" | "Grayscale" | "Black & White";
  detect_edges: boolean;
  auto_enhance: boolean;
  run_ocr: boolean;
  save_enabled: boolean;
  save_folder: string;
  filename_template: string;
  save_format: "searchable_pdf" | "pdf" | "jpg" | "png";
  print_enabled: boolean;
  print_copies: number;
}

export interface ActivityEntry {
  id: string;
  timestamp: string;
  kind: string;
  title: string;
  detail: string;
  output_path: string | null;
}

export interface AppStateData {
  shortcuts: ShortcutConfig[];
  escl_scanners: { id: string; name: string; base_url: string }[];
  recent_activity: ActivityEntry[];
}
