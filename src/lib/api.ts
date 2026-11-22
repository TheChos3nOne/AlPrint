import { convertFileSrc, invoke } from "@tauri-apps/api/core";
import { open, save } from "@tauri-apps/plugin-dialog";
import type {
  ActivityEntry,
  AppStateData,
  DiagnosticReport,
  OcrResult,
  PageEdits,
  PrintRequest,
  PrinterInfo,
  PrinterNetworkStatus,
  ScanPage,
  ScanRequest,
  ScannerInfo,
  ShortcutConfig,
} from "../types";

export function toAssetUrl(filePath: string, cacheBust?: number): string {
  const url = convertFileSrc(filePath);
  return cacheBust ? `${url}${url.includes("?") ? "&" : "?"}t=${cacheBust}` : url;
}

export const api = {
  listPrinters: () => invoke<PrinterInfo[]>("list_printers"),
  getPrinterCapabilities: (printerName: string) =>
    invoke<PrinterInfo>("get_printer_capabilities", { printerName }),
  listScanners: () => invoke<ScannerInfo[]>("list_scanners"),
  probeScannerCapabilities: (scannerId: string) =>
    invoke<ScannerInfo>("probe_wia_scanner_capabilities", { scannerId }),
  addEsclScanner: (baseUrl: string, customName?: string) =>
    invoke<ScannerInfo>("add_escl_scanner", { baseUrl, customName: customName ?? null }),
  removeEsclScanner: (id: string) => invoke<void>("remove_escl_scanner", { id }),
  probeNetworkPrinterStatus: (ip: string) =>
    invoke<PrinterNetworkStatus>("probe_network_printer_status", { ip }),

  scanPage: (request: ScanRequest) => invoke<ScanPage[]>("scan_page", { request }),
  importImageFiles: (sessionId: string, paths: string[]) =>
    invoke<ScanPage[]>("import_image_files", { sessionId, paths }),
  saveBase64Page: (
    sessionId: string,
    dataUrl: string,
    sourceLabel: string,
    dpi: number
  ) =>
    invoke<ScanPage>("save_base64_page", {
      sessionId,
      dataUrl,
      sourceLabel,
      dpi,
    }),
  updatePageEdits: (page: ScanPage, edits: PageEdits) =>
    invoke<ScanPage>("update_page_edits", { page, edits }),
  overwritePageImage: (page: ScanPage, dataUrl: string) =>
    invoke<ScanPage>("overwrite_page_image", { page, dataUrl }),
  stitchIdCardPages: (sessionId: string, frontPath: string, backPath: string) =>
    invoke<ScanPage>("stitch_id_card_pages", {
      sessionId,
      frontPath,
      backPath,
    }),

  readFileBase64: (path: string) => invoke<string>("read_file_base64", { path }),
  exportImages: (
    pagePaths: string[],
    outputDir: string,
    format: "jpg" | "png",
    compression: "low" | "medium" | "high",
    baseName: string
  ) =>
    invoke<string[]>("export_images", {
      pagePaths,
      outputDir,
      format,
      compression,
      baseName,
    }),
  savePdfBytes: (outputPath: string, base64Pdf: string) =>
    invoke<string>("save_pdf_bytes", { outputPath, base64Pdf }),
  saveTextFile: (outputPath: string, content: string) =>
    invoke<string>("save_text_file", { outputPath, content }),

  runOcr: (imagePath: string, language?: string) =>
    invoke<OcrResult>("run_ocr", { imagePath, language: language ?? null }),

  printPages: (request: PrintRequest) => invoke<string>("print_pages", { request }),
  printTestPage: (printerName: string) =>
    invoke<string>("print_test_page", { printerName }),

  runDiagnostics: (printerName: string | null) =>
    invoke<DiagnosticReport>("run_diagnostics", { printerName }),
  clearPrintQueue: (printerName: string) =>
    invoke<string>("clear_print_queue", { printerName }),
  restartSpoolerElevated: () => invoke<string>("restart_spooler_elevated"),
  openPrinterDialog: (printerName: string, mode: "preferences" | "properties") =>
    invoke<void>("open_printer_dialog", { printerName, mode }),
  openPathOrUrl: (target: string) => invoke<void>("open_path_or_url", { target }),

  getAppState: () => invoke<AppStateData>("get_app_state"),
  saveShortcut: (shortcut: ShortcutConfig) =>
    invoke<ShortcutConfig[]>("save_shortcut", { shortcut }),
  deleteShortcut: (id: string) => invoke<ShortcutConfig[]>("delete_shortcut", { id }),
  logActivity: (
    kind: string,
    title: string,
    detail: string,
    outputPath?: string | null
  ) =>
    invoke<ActivityEntry[]>("log_activity", {
      kind,
      title,
      detail,
      outputPath: outputPath ?? null,
    }),
  clearActivity: () => invoke<void>("clear_activity"),
  getDefaultScansDir: () => invoke<string>("get_default_scans_dir"),
};

export async function pickFiles(options: {
  multiple?: boolean;
  filters?: { name: string; extensions: string[] }[];
}): Promise<string[]> {
  const result = await open({
    multiple: options.multiple ?? false,
    filters: options.filters,
  });
  if (!result) return [];
  return Array.isArray(result) ? result : [result];
}

export async function pickFolder(): Promise<string | null> {
  const result = await open({
    directory: true,
    multiple: false,
  });
  if (!result || Array.isArray(result)) return null;
  return result;
}

export async function pickSavePdfPath(defaultName: string): Promise<string | null> {
  return await save({
    defaultPath: defaultName.endsWith(".pdf") ? defaultName : `${defaultName}.pdf`,
    filters: [{ name: "PDF Document", extensions: ["pdf"] }],
  });
}

export async function pickSaveTextPath(defaultName: string): Promise<string | null> {
  return await save({
    defaultPath: defaultName.endsWith(".txt") ? defaultName : `${defaultName}.txt`,
    filters: [{ name: "Plain Text", extensions: ["txt"] }],
  });
}
