import { useState } from "react";
import {
  Edit2,
  FolderOpen,
  Play,
  Plus,
  Trash2,
  X,
  Zap,
} from "lucide-react";
import { api, pickFolder } from "../lib/api";
import { buildPdfBase64 } from "../lib/pdfTools";
import type { OcrResult, PrinterInfo, ScannerInfo, ShortcutConfig } from "../types";

interface ShortcutsViewProps {
  sessionId: string;
  shortcuts: ShortcutConfig[];
  onShortcutsChanged: (list: ShortcutConfig[]) => void;
  scanners: ScannerInfo[];
  selectedScannerId: string;
  printers: PrinterInfo[];
  selectedPrinterName: string;
  onNotify: (msg: string, isError?: boolean) => void;
  onActivityUpdated: () => void;
}

const COLOR_STYLES: Record<string, string> = {
  indigo: "bg-indigo-500/10 text-indigo-600 dark:text-indigo-400",
  emerald: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
  amber: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  sky: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
};

function formatTemplate(tpl: string, smartName?: string): string {
  const now = new Date();
  const date = now.toISOString().slice(0, 10);
  const time = now.toTimeString().slice(0, 8).replace(/:/g, "-");
  return tpl
    .replace(/\{date\}/g, date)
    .replace(/\{time\}/g, time)
    .replace(/\{smart_name\}/g, smartName || "Document");
}

export function ShortcutsView({
  sessionId,
  shortcuts,
  onShortcutsChanged,
  scanners,
  selectedScannerId,
  printers,
  selectedPrinterName,
  onNotify,
  onActivityUpdated,
}: ShortcutsViewProps) {
  const [runningId, setRunningId] = useState<string | null>(null);
  const [progressText, setProgressText] = useState<string>("");
  const [editing, setEditing] = useState<ShortcutConfig | null>(null);

  const activeScanner = scanners.find((s) => s.id === selectedScannerId) ?? scanners[0];
  const activePrinter = printers.find((p) => p.name === selectedPrinterName) ?? printers[0];

  const handleRunShortcut = async (sc: ShortcutConfig) => {
    if (!activeScanner) {
      onNotify("No scanner detected to execute shortcut.", true);
      return;
    }
    setRunningId(sc.id);
    setProgressText("Scanning page from hardware...");
    try {
      const scanned = await api.scanPage({
        session_id: sessionId,
        scanner_id: activeScanner.id,
        backend: activeScanner.backend,
        endpoint: activeScanner.endpoint,
        source: "flatbed",
        page_size: "full",
        color_mode: sc.color_mode,
        dpi: sc.dpi,
        brightness: 0,
        contrast: 0,
        detect_edges: sc.detect_edges,
        auto_enhance: sc.auto_enhance,
        preset: sc.preset === "id_card" ? "document" : sc.preset,
      });

      const ocrMap: Record<string, OcrResult> = {};
      let smartName = "";
      if (sc.run_ocr) {
        setProgressText("Running offline Windows OCR...");
        for (const p of scanned) {
          const ocr = await api.runOcr(p.edited_path);
          ocrMap[p.id] = ocr;
          if (!smartName && ocr.suggested_filename) {
            smartName = ocr.suggested_filename;
          }
        }
      }

      const baseName = formatTemplate(sc.filename_template, smartName);
      let savedLocation: string | null = null;

      if (sc.save_enabled) {
        setProgressText(`Saving ${sc.save_format.toUpperCase()}...`);
        if (sc.save_format === "jpg" || sc.save_format === "png") {
          const paths = await api.exportImages(
            scanned.map((p) => p.edited_path),
            sc.save_folder,
            sc.save_format,
            "low",
            baseName
          );
          savedLocation = paths[0] ?? sc.save_folder;
        } else {
          const b64 = await buildPdfBase64(
            scanned,
            sc.save_format === "searchable_pdf" && sc.run_ocr ? ocrMap : undefined
          );
          const sep = sc.save_folder.endsWith("\\") ? "" : "\\";
          const target = `${sc.save_folder}${sep}${baseName}.pdf`;
          savedLocation = await api.savePdfBytes(target, b64);
        }
      }

      if (sc.print_enabled && activePrinter) {
        setProgressText(`Printing ${sc.print_copies} copy/copies...`);
        await api.printPages({
          printer_name: activePrinter.name,
          page_paths: scanned.map((p) => p.edited_path),
          copies: Math.max(1, sc.print_copies),
          landscape: false,
          paper_size_name: "A4",
          color: sc.color_mode === "Color",
          duplex: "simplex",
          borderless: false,
          fit_mode: "fit",
          job_name: `AlPrint Shortcut: ${sc.name}`,
        });
      }

      await api.logActivity(
        "shortcut",
        `Ran Shortcut: ${sc.name}`,
        `${scanned.length} page(s)${savedLocation ? ` → ${savedLocation}` : ""}`,
        savedLocation
      );
      onActivityUpdated();
      onNotify(`Shortcut "${sc.name}" completed!`);
    } catch (e) {
      onNotify(String(e), true);
    } finally {
      setRunningId(null);
      setProgressText("");
    }
  };

  const handleNewShortcut = async () => {
    const defaultDir = await api.getDefaultScansDir();
    setEditing({
      id: `custom-${Date.now()}`,
      name: "New Custom Shortcut",
      description: "Custom scan, save & print workflow",
      color: "indigo",
      preset: "document",
      dpi: 200,
      color_mode: "Color",
      detect_edges: true,
      auto_enhance: true,
      run_ocr: true,
      save_enabled: true,
      save_folder: defaultDir,
      filename_template: "Scan_{date}_{smart_name}",
      save_format: "searchable_pdf",
      print_enabled: false,
      print_copies: 1,
    });
  };

  const handleSaveShortcut = async () => {
    if (!editing) return;
    try {
      const updated = await api.saveShortcut(editing);
      onShortcutsChanged(updated);
      setEditing(null);
      onNotify(`Saved shortcut "${editing.name}".`);
    } catch (e) {
      onNotify(String(e), true);
    }
  };

  const handleDelete = async (id: string) => {
    try {
      const updated = await api.deleteShortcut(id);
      onShortcutsChanged(updated);
      onNotify("Shortcut removed.");
    } catch (e) {
      onNotify(String(e), true);
    }
  };

  return (
    <div className="p-6 max-w-6xl mx-auto space-y-6 overflow-y-auto h-full">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-xl font-bold text-slate-900 dark:text-white flex items-center gap-2">
            <Zap className="w-5 h-5 text-amber-500" /> One-Click Shortcuts
          </h2>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
            Automate multi-step workflows (Scan → Auto-Crop → OCR → Save to local/synced folder →
            Print) without any cloud login.
          </p>
        </div>
        <button
          onClick={handleNewShortcut}
          className="inline-flex items-center gap-1.5 px-4 py-2 rounded-xl bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-semibold shadow-sm"
        >
          <Plus className="w-4 h-4" /> Create Shortcut
        </button>
      </div>

      {runningId && (
        <div className="rounded-xl bg-indigo-50 dark:bg-indigo-500/15 border border-indigo-200 dark:border-indigo-800 px-4 py-3 text-xs font-medium text-indigo-800 dark:text-indigo-200 flex items-center gap-2">
          <span className="w-2.5 h-2.5 rounded-full bg-indigo-600 animate-ping" />
          {progressText}
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {shortcuts.map((sc) => (
          <div
            key={sc.id}
            className="rounded-2xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 p-5 flex flex-col justify-between gap-4 shadow-sm"
          >
            <div className="space-y-2">
              <div className="flex items-start justify-between gap-3">
                <div className="flex items-center gap-3">
                  <div
                    className={`w-10 h-10 rounded-xl flex items-center justify-center ${
                      COLOR_STYLES[sc.color] ?? COLOR_STYLES.indigo
                    }`}
                  >
                    <Zap className="w-5 h-5" />
                  </div>
                  <div>
                    <h3 className="font-semibold text-slate-900 dark:text-white text-sm">
                      {sc.name}
                    </h3>
                    <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                      {sc.description}
                    </p>
                  </div>
                </div>
                <div className="flex items-center gap-1">
                  <button
                    onClick={() => setEditing(sc)}
                    className="p-1.5 rounded-lg text-slate-400 hover:text-indigo-600 hover:bg-slate-100 dark:hover:bg-slate-800"
                    title="Edit shortcut"
                  >
                    <Edit2 className="w-3.5 h-3.5" />
                  </button>
                  <button
                    onClick={() => handleDelete(sc.id)}
                    className="p-1.5 rounded-lg text-slate-400 hover:text-red-600 hover:bg-slate-100 dark:hover:bg-slate-800"
                    title="Delete shortcut"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>
              </div>

              <div className="flex flex-wrap gap-1.5 pt-1 text-[11px]">
                <span className="px-2 py-0.5 rounded-md bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300">
                  {sc.dpi} DPI · {sc.color_mode}
                </span>
                {sc.detect_edges && (
                  <span className="px-2 py-0.5 rounded-md bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300">
                    Auto-Crop
                  </span>
                )}
                {sc.run_ocr && (
                  <span className="px-2 py-0.5 rounded-md bg-indigo-50 dark:bg-indigo-500/15 text-indigo-600 dark:text-indigo-300">
                    OCR
                  </span>
                )}
                {sc.save_enabled && (
                  <span className="px-2 py-0.5 rounded-md bg-emerald-50 dark:bg-emerald-500/15 text-emerald-700 dark:text-emerald-300">
                    Save {sc.save_format.toUpperCase()}
                  </span>
                )}
                {sc.print_enabled && (
                  <span className="px-2 py-0.5 rounded-md bg-amber-50 dark:bg-amber-500/15 text-amber-700 dark:text-amber-300">
                    Print ×{sc.print_copies}
                  </span>
                )}
              </div>
            </div>

            <button
              onClick={() => handleRunShortcut(sc)}
              disabled={runningId !== null || !activeScanner}
              className="w-full flex items-center justify-center gap-2 py-2.5 rounded-xl bg-slate-900 dark:bg-indigo-600 hover:bg-indigo-600 dark:hover:bg-indigo-700 disabled:opacity-40 text-white text-xs font-semibold transition"
            >
              <Play className="w-3.5 h-3.5" />
              {runningId === sc.id ? "Running..." : "Run Shortcut Now"}
            </button>
          </div>
        ))}
      </div>

      {/* Create / Edit Shortcut Modal */}
      {editing && (
        <div className="fixed inset-0 z-50 bg-slate-950/60 flex items-center justify-center p-4">
          <div className="w-full max-w-lg rounded-2xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 p-6 space-y-4 shadow-2xl max-h-[90vh] overflow-y-auto">
            <div className="flex items-center justify-between">
              <h3 className="font-semibold text-base">Configure Shortcut</h3>
              <button onClick={() => setEditing(null)}>
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="space-y-3 text-xs">
              <div>
                <label className="font-medium block mb-1">Shortcut Name</label>
                <input
                  type="text"
                  value={editing.name}
                  onChange={(e) => setEditing({ ...editing, name: e.target.value })}
                  className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
                />
              </div>

              <div>
                <label className="font-medium block mb-1">Description</label>
                <input
                  type="text"
                  value={editing.description}
                  onChange={(e) => setEditing({ ...editing, description: e.target.value })}
                  className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
                />
              </div>

              <div className="grid grid-cols-3 gap-2">
                <div>
                  <label className="font-medium block mb-1">Preset</label>
                  <select
                    value={editing.preset}
                    onChange={(e) =>
                      setEditing({
                        ...editing,
                        preset: e.target.value as ShortcutConfig["preset"],
                      })
                    }
                    className="w-full px-2.5 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
                  >
                    <option value="document">Document</option>
                    <option value="photo">Photo</option>
                    <option value="multi_item">Multi-Item</option>
                    <option value="book_spread">Book Spread</option>
                  </select>
                </div>
                <div>
                  <label className="font-medium block mb-1">DPI</label>
                  <select
                    value={editing.dpi}
                    onChange={(e) => setEditing({ ...editing, dpi: Number(e.target.value) })}
                    className="w-full px-2.5 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
                  >
                    {[150, 200, 300, 600].map((d) => (
                      <option key={d} value={d}>
                        {d} DPI
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="font-medium block mb-1">Color</label>
                  <select
                    value={editing.color_mode}
                    onChange={(e) =>
                      setEditing({
                        ...editing,
                        color_mode: e.target.value as ShortcutConfig["color_mode"],
                      })
                    }
                    className="w-full px-2.5 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
                  >
                    <option value="Color">Color</option>
                    <option value="Grayscale">Grayscale</option>
                    <option value="Black & White">Black &amp; White</option>
                  </select>
                </div>
              </div>

              <div className="flex flex-wrap gap-4 py-1">
                <label className="flex items-center gap-1.5 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={editing.detect_edges}
                    onChange={(e) => setEditing({ ...editing, detect_edges: e.target.checked })}
                  />
                  Auto-Crop
                </label>
                <label className="flex items-center gap-1.5 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={editing.auto_enhance}
                    onChange={(e) => setEditing({ ...editing, auto_enhance: e.target.checked })}
                  />
                  Auto-Enhance
                </label>
                <label className="flex items-center gap-1.5 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={editing.run_ocr}
                    onChange={(e) => setEditing({ ...editing, run_ocr: e.target.checked })}
                  />
                  Run Offline OCR
                </label>
              </div>

              <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3 space-y-2.5">
                <label className="flex items-center gap-2 font-semibold cursor-pointer">
                  <input
                    type="checkbox"
                    checked={editing.save_enabled}
                    onChange={(e) => setEditing({ ...editing, save_enabled: e.target.checked })}
                  />
                  Save Output to Folder
                </label>

                {editing.save_enabled && (
                  <>
                    <div className="flex gap-2">
                      <input
                        type="text"
                        value={editing.save_folder}
                        onChange={(e) => setEditing({ ...editing, save_folder: e.target.value })}
                        className="flex-1 px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
                      />
                      <button
                        onClick={async () => {
                          const f = await pickFolder();
                          if (f) setEditing({ ...editing, save_folder: f });
                        }}
                        className="px-2.5 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 flex items-center gap-1"
                      >
                        <FolderOpen className="w-3.5 h-3.5" /> Browse
                      </button>
                    </div>
                    <div className="grid grid-cols-2 gap-2">
                      <div>
                        <label className="text-[11px] text-slate-500 block mb-1">
                          Template ({"{date}"}, {"{time}"}, {"{smart_name}"})
                        </label>
                        <input
                          type="text"
                          value={editing.filename_template}
                          onChange={(e) =>
                            setEditing({ ...editing, filename_template: e.target.value })
                          }
                          className="w-full px-2.5 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
                        />
                      </div>
                      <div>
                        <label className="text-[11px] text-slate-500 block mb-1">Format</label>
                        <select
                          value={editing.save_format}
                          onChange={(e) =>
                            setEditing({
                              ...editing,
                              save_format: e.target.value as ShortcutConfig["save_format"],
                            })
                          }
                          className="w-full px-2.5 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
                        >
                          <option value="searchable_pdf">Searchable PDF</option>
                          <option value="pdf">Standard PDF</option>
                          <option value="jpg">JPEG Image</option>
                          <option value="png">PNG Image</option>
                        </select>
                      </div>
                    </div>
                  </>
                )}
              </div>

              <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-3 flex items-center justify-between">
                <label className="flex items-center gap-2 font-semibold cursor-pointer">
                  <input
                    type="checkbox"
                    checked={editing.print_enabled}
                    onChange={(e) => setEditing({ ...editing, print_enabled: e.target.checked })}
                  />
                  Auto-Print Copies
                </label>
                {editing.print_enabled && (
                  <input
                    type="number"
                    min={1}
                    max={20}
                    value={editing.print_copies}
                    onChange={(e) =>
                      setEditing({ ...editing, print_copies: Math.max(1, Number(e.target.value)) })
                    }
                    className="w-20 px-2.5 py-1 rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
                  />
                )}
              </div>
            </div>

            <div className="flex justify-end gap-2 pt-2">
              <button
                onClick={() => setEditing(null)}
                className="px-4 py-2 rounded-xl text-xs font-medium border border-slate-200 dark:border-slate-700"
              >
                Cancel
              </button>
              <button
                onClick={handleSaveShortcut}
                className="px-4 py-2 rounded-xl text-xs font-semibold bg-indigo-600 text-white hover:bg-indigo-700"
              >
                Save Shortcut
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
