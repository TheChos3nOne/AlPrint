import { useEffect, useMemo, useState } from "react";
import {
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  FileText,
  FolderOpen,
  Layers,
  Printer,
  RotateCw,
  X,
} from "lucide-react";
import { api, pickFiles, toAssetUrl } from "../lib/api";
import { rasterizePdfFile } from "../lib/pdfTools";
import type { PaperSizeInfo, PrinterInfo, ScanPage } from "../types";

interface PrintDocumentsViewProps {
  sessionId: string;
  printers: PrinterInfo[];
  selectedPrinterName: string;
  onSelectPrinter: (name: string) => void;
  incomingPages: ScanPage[];
  onClearIncoming: () => void;
  onNotify: (msg: string, isError?: boolean) => void;
  onActivityUpdated: () => void;
}

interface DocPageItem {
  id: string;
  label: string;
  srcUrl: string;
  filePath?: string; // If already saved on disk
  dataUrl?: string;  // If rasterized from PDF in memory
  width: number;
  height: number;
}

function parsePageRange(expr: string, total: number): number[] {
  if (!expr.trim()) return Array.from({ length: total }, (_, i) => i);
  const out = new Set<number>();
  for (const part of expr.split(",")) {
    const trimmed = part.trim();
    if (!trimmed) continue;
    if (trimmed.includes("-")) {
      const [aStr, bStr] = trimmed.split("-");
      const a = Math.max(1, parseInt(aStr, 10) || 1);
      const b = Math.min(total, parseInt(bStr, 10) || total);
      for (let i = Math.min(a, b); i <= Math.max(a, b); i++) out.add(i - 1);
    } else {
      const n = parseInt(trimmed, 10);
      if (n >= 1 && n <= total) out.add(n - 1);
    }
  }
  return Array.from(out).sort((a, b) => a - b);
}

async function loadDrawableImage(item: DocPageItem): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.onload = () => resolve(img);
    img.onerror = (err) => reject(err);
    img.src = item.dataUrl ?? item.srcUrl;
  });
}

function resolvePaperMm(
  paperSizeName: string,
  printerSizes?: PaperSizeInfo[]
): { w: number; h: number } {
  if (printerSizes?.length) {
    const exact = printerSizes.find(
      (s) => s.name.toLowerCase() === paperSizeName.toLowerCase() && s.width_mm > 0 && s.height_mm > 0
    );
    if (exact) return { w: exact.width_mm, h: exact.height_mm };
  }
  const n = paperSizeName.toLowerCase().replace(/[^a-z0-9]/g, "");
  if (n.includes("letter")) return { w: 215.9, h: 279.4 };
  if (n.includes("legal")) return { w: 215.9, h: 355.6 };
  if (n.includes("a5")) return { w: 148, h: 210 };
  if (n.includes("a3")) return { w: 297, h: 420 };
  if (n.includes("4x6") || n.includes("10x15")) return { w: 101.6, h: 152.4 };
  if (n.includes("5x7") || n.includes("13x18")) return { w: 127, h: 177.8 };
  if (n.includes("8x10")) return { w: 203.2, h: 254 };
  if (n.includes("b5")) return { w: 176, h: 250 };
  return { w: 210, h: 297 };
}

async function composeNupSheetDataUrl(
  items: DocPageItem[],
  perSheet: 2 | 4,
  landscape: boolean,
  sheetAspect: number
): Promise<string> {
  const cols = perSheet === 2 ? (landscape ? 2 : 1) : 2;
  const rows = perSheet === 2 ? (landscape ? 1 : 2) : 2;
  const longSide = 2338;
  const safeAspect = Number.isFinite(sheetAspect) && sheetAspect > 0 ? sheetAspect : landscape ? 297 / 210 : 210 / 297;
  const canvas = document.createElement("canvas");
  canvas.width = safeAspect >= 1 ? longSide : Math.round(longSide * safeAspect);
  canvas.height = safeAspect >= 1 ? Math.round(longSide / safeAspect) : longSide;
  const ctx = canvas.getContext("2d")!;
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  const pad = Math.round(Math.min(canvas.width, canvas.height) * 0.02);
  const cellW = (canvas.width - pad * (cols + 1)) / cols;
  const cellH = (canvas.height - pad * (rows + 1)) / rows;

  for (let i = 0; i < items.length; i++) {
    const img = await loadDrawableImage(items[i]);
    const col = i % cols;
    const row = Math.floor(i / cols);
    const x0 = pad + col * (cellW + pad);
    const y0 = pad + row * (cellH + pad);
    const scale = Math.min(cellW / img.width, cellH / img.height);
    const dw = img.width * scale;
    const dh = img.height * scale;
    ctx.drawImage(img, x0 + (cellW - dw) / 2, y0 + (cellH - dh) / 2, dw, dh);
  }
  return canvas.toDataURL("image/png");
}

function createBlankPageDataUrl(landscape: boolean): string {
  const canvas = document.createElement("canvas");
  canvas.width = landscape ? 141 : 100;
  canvas.height = landscape ? 100 : 141;
  const ctx = canvas.getContext("2d")!;
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  return canvas.toDataURL("image/png");
}

export function PrintDocumentsView({
  sessionId,
  printers,
  selectedPrinterName,
  onSelectPrinter,
  incomingPages,
  onClearIncoming,
  onNotify,
  onActivityUpdated,
}: PrintDocumentsViewProps) {
  const activePrinter = printers.find((p) => p.name === selectedPrinterName) ?? printers[0];

  const [pages, setPages] = useState<DocPageItem[]>([]);
  const [activeSheetIdx, setActiveSheetIdx] = useState(0);
  const [loadingFiles, setLoadingFiles] = useState(false);

  const [copies, setCopies] = useState(1);
  const [rangeMode, setRangeMode] = useState<"all" | "current" | "custom">("all");
  const [customRange, setCustomRange] = useState("1-2");
  const [orientation, setOrientation] = useState<"portrait" | "landscape">("portrait");
  const [paperSize, setPaperSize] = useState("A4");
  const [color, setColor] = useState(true);
  const [fitMode, setFitMode] = useState<"fit" | "fill">("fit");
  const [marginMode, setMarginMode] = useState<"minimal" | "normal" | "none">("minimal");
  const [scalePercent, setScalePercent] = useState(100);
  const [pagesPerSheet, setPagesPerSheet] = useState<1 | 2 | 4>(1);
  const [duplexMode, setDuplexMode] = useState<
    "simplex" | "long_edge" | "short_edge" | "manual_duplex"
  >("simplex");

  const [printing, setPrinting] = useState(false);
  const [manualDuplexModal, setManualDuplexModal] = useState<{
    pass1Paths: string[];
    pass2Paths: string[];
    paperSize: string;
    addedBlankBack: boolean;
    marginMm: number | undefined;
    borderless: boolean;
    scalePercent: number;
  } | null>(null);

  const portraitMm = useMemo(
    () => resolvePaperMm(paperSize, activePrinter?.paper_sizes),
    [paperSize, activePrinter]
  );
  const sheetMmW = orientation === "landscape" ? portraitMm.h : portraitMm.w;
  const sheetMmH = orientation === "landscape" ? portraitMm.w : portraitMm.h;
  const sheetAspect = sheetMmW / Math.max(1, sheetMmH);

  useEffect(() => {
    if (incomingPages.length === 0) return;
    setPages(
      incomingPages.map((p, idx) => ({
        id: p.id,
        label: `${p.source_label} (p.${idx + 1})`,
        srcUrl: toAssetUrl(p.edited_path, Date.now()),
        filePath: p.edited_path,
        width: p.width,
        height: p.height,
      }))
    );
    setActiveSheetIdx(0);
    onClearIncoming();
  }, [incomingPages, onClearIncoming]);

  useEffect(() => {
    if (!activePrinter?.paper_sizes?.length) return;
    if (!activePrinter.paper_sizes.some((s) => s.name === paperSize)) {
      const a4 = activePrinter.paper_sizes.find((s) => s.name.toLowerCase().includes("a4"));
      const letter = activePrinter.paper_sizes.find((s) =>
        s.name.toLowerCase().includes("letter")
      );
      setPaperSize((a4 ?? letter ?? activePrinter.paper_sizes[0]).name);
    }
  }, [activePrinter, paperSize]);

  const handlePickFiles = async () => {
    const paths = await pickFiles({
      multiple: true,
      filters: [
        {
          name: "Printable Documents & Images",
          extensions: ["pdf", "png", "jpg", "jpeg", "bmp", "webp", "tif", "tiff"],
        },
      ],
    });
    if (paths.length === 0) return;

    setLoadingFiles(true);
    try {
      const loaded: DocPageItem[] = [];
      for (const p of paths) {
        const lower = p.toLowerCase();
        const fname = p.split(/[\\/]/).pop() ?? "Document";
        if (lower.endsWith(".pdf")) {
          const rendered = await rasterizePdfFile(p, 3.0);
          for (const r of rendered) {
            loaded.push({
              id: `${p}-${r.pageNumber}`,
              label: `${fname} · Page ${r.pageNumber}`,
              srcUrl: r.dataUrl,
              dataUrl: r.dataUrl,
              width: r.width,
              height: r.height,
            });
          }
        } else {
          const imported = await api.importImageFiles(sessionId, [p]);
          for (const imp of imported) {
            loaded.push({
              id: imp.id,
              label: fname,
              srcUrl: toAssetUrl(imp.edited_path, Date.now()),
              filePath: imp.edited_path,
              width: imp.width,
              height: imp.height,
            });
          }
        }
      }
      setPages(loaded);
      setActiveSheetIdx(0);
      onNotify(`Loaded ${loaded.length} page(s) for printing.`);
    } catch (e) {
      onNotify(String(e), true);
    } finally {
      setLoadingFiles(false);
    }
  };

  const selectedIndices = useMemo(() => {
    if (pages.length === 0) return [];
    if (rangeMode === "current") {
      const safe = Math.min(activeSheetIdx * pagesPerSheet, pages.length - 1);
      return [safe];
    }
    if (rangeMode === "custom") {
      return parsePageRange(customRange, pages.length);
    }
    return pages.map((_, i) => i);
  }, [pages, rangeMode, customRange, activeSheetIdx, pagesPerSheet]);

  const sheets = useMemo(() => {
    const items = selectedIndices.map((i) => pages[i]).filter(Boolean);
    const groups: DocPageItem[][] = [];
    for (let i = 0; i < items.length; i += pagesPerSheet) {
      groups.push(items.slice(i, i + pagesPerSheet));
    }
    return groups;
  }, [selectedIndices, pages, pagesPerSheet]);

  const ensureDiskPaths = async (): Promise<string[]> => {
    const diskPaths: string[] = [];
    for (let sIdx = 0; sIdx < sheets.length; sIdx++) {
      const group = sheets[sIdx];
      if (pagesPerSheet === 1 && group[0]?.filePath) {
        diskPaths.push(group[0].filePath);
      } else if (pagesPerSheet === 1 && group[0]?.dataUrl) {
        const saved = await api.saveBase64Page(
          sessionId,
          group[0].dataUrl,
          group[0].label,
          150
        );
        diskPaths.push(saved.edited_path);
      } else {
        const composedDataUrl = await composeNupSheetDataUrl(
          group,
          pagesPerSheet as 2 | 4,
          orientation === "landscape",
          sheetAspect
        );
        const saved = await api.saveBase64Page(
          sessionId,
          composedDataUrl,
          `Sheet ${sIdx + 1}`,
          200
        );
        diskPaths.push(saved.edited_path);
      }
    }
    return diskPaths;
  };

  const resolvedMarginMm =
    marginMode === "none" ? 0 : marginMode === "normal" ? 12 : undefined;

  const handlePrint = async () => {
    if (!activePrinter) {
      onNotify("Please select a printer first.", true);
      return;
    }
    if (sheets.length === 0) {
      onNotify("No pages selected to print.", true);
      return;
    }

    setPrinting(true);
    try {
      const diskPaths = await ensureDiskPaths();

      if (duplexMode === "manual_duplex" && diskPaths.length > 1) {
        const paddedPaths = [...diskPaths];
        let addedBlankBack = false;
        if (paddedPaths.length % 2 === 1) {
          const blankDataUrl = createBlankPageDataUrl(orientation === "landscape");
          const savedBlank = await api.saveBase64Page(
            sessionId,
            blankDataUrl,
            "Blank Back Page",
            72
          );
          paddedPaths.push(savedBlank.edited_path);
          addedBlankBack = true;
        }

        const oddPaths = paddedPaths.filter((_, i) => i % 2 === 0);
        const evenPaths = paddedPaths.filter((_, i) => i % 2 === 1).reverse();

        await api.printPages({
          printer_name: activePrinter.name,
          page_paths: oddPaths,
          copies,
          landscape: orientation === "landscape",
          paper_size_name: paperSize,
          color: activePrinter.supports_color ? color : false,
          duplex: "simplex",
          borderless: marginMode === "none",
          fit_mode: fitMode,
          job_name: "AlPrint Manual Duplex (Pass 1 - Odd Pages)",
          margin_mm: resolvedMarginMm,
          scale_percent: scalePercent,
        });

        setManualDuplexModal({
          pass1Paths: oddPaths,
          pass2Paths: evenPaths,
          paperSize,
          addedBlankBack,
          marginMm: resolvedMarginMm,
          borderless: marginMode === "none",
          scalePercent,
        });
        onNotify("Pass 1 (odd pages) sent! Follow the flip prompt for Pass 2.");
        return;
      }

      const msg = await api.printPages({
        printer_name: activePrinter.name,
        page_paths: diskPaths,
        copies,
        landscape: orientation === "landscape",
        paper_size_name: paperSize,
        color: activePrinter.supports_color ? color : false,
        duplex:
          duplexMode === "manual_duplex" || !activePrinter.supports_duplex
            ? "simplex"
            : duplexMode,
        borderless: marginMode === "none",
        fit_mode: fitMode,
        job_name: "AlPrint Document",
        margin_mm: resolvedMarginMm,
        scale_percent: scalePercent,
      });
      onActivityUpdated();
      onNotify(msg);
    } catch (e) {
      onNotify(String(e), true);
    } finally {
      setPrinting(false);
    }
  };

  const handleManualDuplexPass2 = async () => {
    if (!manualDuplexModal || !activePrinter) return;
    setPrinting(true);
    try {
      const msg = await api.printPages({
        printer_name: activePrinter.name,
        page_paths: manualDuplexModal.pass2Paths,
        copies,
        landscape: orientation === "landscape",
        paper_size_name: manualDuplexModal.paperSize,
        color: activePrinter.supports_color ? color : false,
        duplex: "simplex",
        borderless: manualDuplexModal.borderless,
        fit_mode: fitMode,
        job_name: "AlPrint Manual Duplex (Pass 2 - Even Pages)",
        margin_mm: manualDuplexModal.marginMm,
        scale_percent: manualDuplexModal.scalePercent,
      });
      setManualDuplexModal(null);
      onActivityUpdated();
      onNotify(msg);
    } catch (e) {
      onNotify(String(e), true);
    } finally {
      setPrinting(false);
    }
  };

  const currentSheet = sheets[Math.min(activeSheetIdx, Math.max(0, sheets.length - 1))] ?? [];

  return (
    <div className="h-full flex overflow-hidden">
      {/* Left Print Settings */}
      <div className="w-80 shrink-0 border-r border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 flex flex-col">
        <div className="p-4 border-b border-slate-100 dark:border-slate-800 flex items-center justify-between">
          <h2 className="text-sm font-semibold flex items-center gap-2">
            <FileText className="w-4 h-4 text-indigo-500" /> Print Settings
          </h2>
          <button
            onClick={handlePickFiles}
            disabled={loadingFiles}
            className="text-xs font-medium text-indigo-600 dark:text-indigo-400 hover:underline flex items-center gap-1"
          >
            <FolderOpen className="w-3.5 h-3.5" /> Open File
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-4 space-y-4 text-xs">
          <div>
            <label className="font-semibold text-slate-600 dark:text-slate-300 block mb-1">
              Printer
            </label>
            <select
              value={activePrinter?.name ?? ""}
              onChange={(e) => onSelectPrinter(e.target.value)}
              className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 font-medium"
            >
              {printers.map((p) => {
                const tag = p.is_network
                  ? "Wi-Fi"
                  : p.port_name.startsWith("USB")
                  ? "USB"
                  : "Local";
                return (
                  <option key={p.name} value={p.name}>
                    {p.name} [{tag}]{p.is_offline ? " (Offline)" : ""}
                  </option>
                );
              })}
            </select>
          </div>

          <div className="grid grid-cols-2 gap-2.5">
            <div>
              <label className="font-medium text-slate-500 block mb-1">Copies</label>
              <input
                type="number"
                min={1}
                max={activePrinter?.max_copies ?? 99}
                value={copies}
                onChange={(e) => setCopies(Math.max(1, Number(e.target.value)))}
                className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
              />
            </div>

            <div>
              <label className="font-medium text-slate-500 block mb-1">Orientation</label>
              <select
                value={orientation}
                onChange={(e) => setOrientation(e.target.value as "portrait" | "landscape")}
                className="w-full px-2.5 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
              >
                <option value="portrait">Portrait</option>
                <option value="landscape">Landscape</option>
              </select>
            </div>
          </div>

          <div>
            <label className="font-medium text-slate-500 block mb-1">Pages to Print</label>
            <select
              value={rangeMode}
              onChange={(e) => setRangeMode(e.target.value as "all" | "current" | "custom")}
              className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
            >
              <option value="all">All Pages ({pages.length})</option>
              <option value="current">Current Page Only</option>
              <option value="custom">Custom Range (e.g. 1-3, 5)</option>
            </select>
            {rangeMode === "custom" && (
              <input
                type="text"
                value={customRange}
                onChange={(e) => setCustomRange(e.target.value)}
                placeholder="e.g. 1-3, 5"
                className="mt-2 w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
              />
            )}
          </div>

          <div>
            <label className="font-medium text-slate-500 block mb-1">Paper Size</label>
            <select
              value={paperSize}
              onChange={(e) => setPaperSize(e.target.value)}
              className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
            >
              {(activePrinter?.paper_sizes ?? [{ name: "A4" }, { name: "Letter" }]).map((s) => (
                <option key={s.name} value={s.name}>
                  {s.name}
                </option>
              ))}
            </select>
          </div>

          <div className="grid grid-cols-2 gap-2.5">
            <div>
              <label className="font-medium text-slate-500 block mb-1">Color Mode</label>
              <select
                value={color ? "color" : "gray"}
                disabled={activePrinter && !activePrinter.supports_color}
                onChange={(e) => setColor(e.target.value === "color")}
                className="w-full px-2.5 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
              >
                <option value="color">Color</option>
                <option value="gray">Grayscale</option>
              </select>
            </div>

            <div>
              <label className="font-medium text-slate-500 block mb-1">Scaling</label>
              <select
                value={fitMode}
                onChange={(e) => setFitMode(e.target.value as "fit" | "fill")}
                className="w-full px-2.5 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
              >
                <option value="fit">Fit to Page</option>
                <option value="fill">Fill Page (Crop)</option>
              </select>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-2.5 items-end">
            <div>
              <label className="font-medium text-slate-500 block mb-1">Margins</label>
              <select
                value={marginMode}
                onChange={(e) =>
                  setMarginMode(e.target.value as "minimal" | "normal" | "none")
                }
                className="w-full px-2.5 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
              >
                <option value="minimal">Minimal (Fit Printable Area)</option>
                <option value="normal">Standard (+12 mm Margin)</option>
                <option value="none">None (Use PDF's Own Margins / 1:1)</option>
              </select>
            </div>

            <div>
              <div className="flex justify-between font-medium text-slate-500 mb-1">
                <span>Scale</span>
                <span>{scalePercent}%</span>
              </div>
              <input
                type="range"
                min={50}
                max={100}
                step={5}
                value={scalePercent}
                onChange={(e) => setScalePercent(Number(e.target.value))}
                className="w-full accent-indigo-600 my-1.5"
              />
            </div>
          </div>

          <div>
            <label className="font-medium text-slate-500 block mb-1">Pages per Sheet (N-up)</label>
            <div className="grid grid-cols-3 gap-1.5">
              {([1, 2, 4] as const).map((n) => (
                <button
                  key={n}
                  onClick={() => {
                    setPagesPerSheet(n);
                    setActiveSheetIdx(0);
                  }}
                  className={`py-2 rounded-xl border font-semibold transition ${
                    pagesPerSheet === n
                      ? "border-indigo-500 bg-indigo-50 dark:bg-indigo-500/20 text-indigo-700 dark:text-indigo-300"
                      : "border-slate-200 dark:border-slate-700"
                  }`}
                >
                  {n} / sheet
                </button>
              ))}
            </div>
          </div>

          <div>
            <label className="font-medium text-slate-500 block mb-1.5">Two-Sided (Duplex)</label>
            <div className="grid grid-cols-2 gap-1.5">
              {(
                [
                  {
                    id: "simplex",
                    label: "One-Sided",
                    sub: "Single face",
                    disabled: false,
                    icon: (
                      <svg viewBox="0 0 24 24" className="w-4 h-4 shrink-0" fill="none" stroke="currentColor" strokeWidth="1.8">
                        <rect x="6" y="3" width="12" height="18" rx="2" />
                        <path d="M9 8h6M9 12h6M9 16h4" />
                      </svg>
                    ),
                  },
                  {
                    id: "long_edge",
                    label: "Long Edge",
                    sub: activePrinter?.supports_duplex ? "Flip like book" : "N/A on printer",
                    disabled: !activePrinter?.supports_duplex,
                    icon: (
                      <svg viewBox="0 0 24 24" className="w-4 h-4 shrink-0" fill="none" stroke="currentColor" strokeWidth="1.8">
                        <rect x="4" y="4" width="7" height="16" rx="1.2" />
                        <rect x="13" y="4" width="7" height="16" rx="1.2" strokeDasharray="2 2" />
                        <path d="M9 12h6m-2-2 2 2-2 2" />
                      </svg>
                    ),
                  },
                  {
                    id: "short_edge",
                    label: "Short Edge",
                    sub: activePrinter?.supports_duplex ? "Flip like pad" : "N/A on printer",
                    disabled: !activePrinter?.supports_duplex,
                    icon: (
                      <svg viewBox="0 0 24 24" className="w-4 h-4 shrink-0" fill="none" stroke="currentColor" strokeWidth="1.8">
                        <rect x="5" y="3" width="14" height="8" rx="1.2" />
                        <rect x="5" y="13" width="14" height="8" rx="1.2" strokeDasharray="2 2" />
                        <path d="M12 9v6m-2-2 2 2 2-2" />
                      </svg>
                    ),
                  },
                  {
                    id: "manual_duplex",
                    label: "Manual 2-Pass",
                    sub: "Guided flip",
                    disabled: false,
                    icon: (
                      <svg viewBox="0 0 24 24" className="w-4 h-4 shrink-0" fill="none" stroke="currentColor" strokeWidth="1.8">
                        <rect x="5" y="4" width="10" height="14" rx="1.5" />
                        <path d="M17 8a4 4 0 0 1 0 8m0 0-2-1.5m2 1.5 1.5-2" />
                      </svg>
                    ),
                  },
                ] as const
              ).map((opt) => (
                <button
                  key={opt.id}
                  type="button"
                  disabled={opt.disabled}
                  onClick={() => setDuplexMode(opt.id)}
                  className={`flex items-center gap-2 p-2 rounded-xl border text-left transition ${
                    duplexMode === opt.id
                      ? "border-indigo-500 bg-indigo-50 dark:bg-indigo-500/20 text-indigo-700 dark:text-indigo-300"
                      : "border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800"
                  } disabled:opacity-40 disabled:cursor-not-allowed`}
                >
                  {opt.icon}
                  <div className="min-w-0">
                    <div className="font-semibold leading-tight truncate">{opt.label}</div>
                    <div className="text-[10px] opacity-75 truncate">{opt.sub}</div>
                  </div>
                </button>
              ))}
            </div>
            {duplexMode === "manual_duplex" && (
              <p className="text-[11px] text-indigo-600 dark:text-indigo-400 mt-1.5">
                Prints odd pages first, then guides you to flip the stack for even pages.
              </p>
            )}
          </div>
        </div>

        <div className="p-4 border-t border-slate-100 dark:border-slate-800">
          <button
            onClick={handlePrint}
            disabled={printing || sheets.length === 0 || !activePrinter}
            className="w-full flex items-center justify-center gap-2 py-3 px-4 rounded-xl bg-indigo-600 hover:bg-indigo-700 disabled:opacity-40 text-white text-sm font-semibold shadow-sm"
          >
            <Printer className="w-4 h-4" />
            {printing
              ? "Sending Job..."
              : `Print ${sheets.length} Sheet${sheets.length === 1 ? "" : "s"}`}
          </button>
        </div>
      </div>

      {/* Live Preview Stage */}
      <div className="flex-1 flex flex-col bg-slate-100 dark:bg-slate-950 min-w-0">
        {pages.length === 0 ? (
          <div className="flex-1 flex flex-col items-center justify-center p-8 text-center">
            <div className="w-16 h-16 rounded-2xl bg-emerald-50 dark:bg-emerald-500/15 text-emerald-600 flex items-center justify-center mb-4">
              <FileText className="w-8 h-8" />
            </div>
            <h3 className="text-base font-semibold text-slate-800 dark:text-slate-100">
              Select a PDF or Document Image to Print
            </h3>
            <p className="text-xs text-slate-500 dark:text-slate-400 max-w-md mt-1.5 mb-5">
              Preview pages offline with N-up sheet layout, grayscale preview, custom page ranges,
              and guided manual two-sided printing.
            </p>
            <button
              onClick={handlePickFiles}
              disabled={loadingFiles}
              className="inline-flex items-center gap-2 px-5 py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-semibold"
            >
              <FolderOpen className="w-4 h-4" />
              {loadingFiles ? "Loading..." : "Browse Documents"}
            </button>
          </div>
        ) : (
          <>
            <div className="flex-1 min-h-0 p-8 flex items-center justify-center overflow-auto">
              {(() => {
                // Proportional physical sizing relative to A4 (297mm = 580px) so Letter, Legal, A5, and 4x6 visibly resize
                const baseScalePx = 580 / 297;
                const rawW = sheetMmW * baseScalePx;
                const rawH = sheetMmH * baseScalePx;
                const clamp = Math.min(1, 660 / Math.max(1, rawW), 580 / Math.max(1, rawH));
                const boxW = Math.max(180, Math.round(rawW * clamp));
                const boxH = Math.max(180, Math.round(rawH * clamp));
                const marginPadPx =
                  marginMode === "none" || fitMode === "fill"
                    ? 0
                    : marginMode === "normal"
                    ? Math.max(8, Math.round(boxW * (12 / Math.max(1, sheetMmW))))
                    : Math.max(4, Math.round(boxW * (3 / Math.max(1, sheetMmW))));

                return (
                  <div
                    style={{
                      width: `${boxW}px`,
                      height: `${boxH}px`,
                      padding: `${marginPadPx}px`,
                    }}
                    className="bg-white shadow-xl rounded-md transition-all duration-150 flex items-center justify-center overflow-hidden"
                  >
                    <div
                      style={{
                        transform: `scale(${scalePercent / 100})`,
                        transformOrigin: "center",
                      }}
                      className={`w-full h-full transition-transform duration-150 grid gap-2.5 ${
                        pagesPerSheet === 1
                          ? "grid-cols-1 grid-rows-1"
                          : pagesPerSheet === 2
                          ? orientation === "landscape"
                            ? "grid-cols-2 grid-rows-1"
                            : "grid-cols-1 grid-rows-2"
                          : "grid-cols-2 grid-rows-2"
                      }`}
                    >
                      {currentSheet.map((item) => (
                        <div
                          key={item.id}
                          className="w-full h-full flex items-center justify-center overflow-hidden"
                        >
                          <img
                            src={item.srcUrl}
                            alt={item.label}
                            style={{ filter: color ? "none" : "grayscale(100%)" }}
                            className={`w-full h-full ${
                              fitMode === "fill" ? "object-cover" : "object-contain"
                            }`}
                          />
                        </div>
                      ))}
                    </div>
                  </div>
                );
              })()}
            </div>

            <div className="px-6 py-3 bg-white dark:bg-slate-900 border-t border-slate-200 dark:border-slate-800 flex items-center justify-between text-xs">
              <span className="text-slate-500">
                Sheet {Math.min(activeSheetIdx + 1, sheets.length)} of {sheets.length} ·{" "}
                {selectedIndices.length} page{selectedIndices.length === 1 ? "" : "s"} ·{" "}
                <strong className="text-slate-700 dark:text-slate-300">
                  {paperSize} ({Math.round(sheetMmW)} × {Math.round(sheetMmH)} mm)
                </strong>
              </span>
              <div className="flex items-center gap-2">
                <button
                  onClick={() => setActiveSheetIdx((i) => Math.max(0, i - 1))}
                  disabled={activeSheetIdx === 0}
                  className="p-1.5 rounded-lg border border-slate-200 dark:border-slate-700 disabled:opacity-30"
                >
                  <ChevronLeft className="w-4 h-4" />
                </button>
                <button
                  onClick={() => setActiveSheetIdx((i) => Math.min(sheets.length - 1, i + 1))}
                  disabled={activeSheetIdx >= sheets.length - 1}
                  className="p-1.5 rounded-lg border border-slate-200 dark:border-slate-700 disabled:opacity-30"
                >
                  <ChevronRight className="w-4 h-4" />
                </button>
              </div>
            </div>
          </>
        )}
      </div>

      {/* Guided Manual Duplex Modal */}
      {manualDuplexModal && (
        <div className="fixed inset-0 z-50 bg-slate-950/70 flex items-center justify-center p-4">
          <div className="w-full max-w-md rounded-2xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 p-6 space-y-4 shadow-2xl">
            <div className="flex items-center justify-between">
              <h3 className="font-semibold flex items-center gap-2">
                <Layers className="w-4 h-4 text-indigo-500" /> Manual Two-Sided Step 2
              </h3>
              <button onClick={() => setManualDuplexModal(null)}>
                <X className="w-4 h-4" />
              </button>
            </div>
            <div className="rounded-xl bg-indigo-50 dark:bg-indigo-500/15 p-4 space-y-2 text-xs text-slate-700 dark:text-slate-200">
              <div className="font-semibold flex items-center gap-1.5 text-indigo-700 dark:text-indigo-300">
                <CheckCircle2 className="w-4 h-4" /> Pass 1 ({manualDuplexModal.pass1Paths.length}{" "}
                odd page(s)) sent to printer!
              </div>
              <ol className="list-decimal list-inside space-y-1.5 leading-relaxed">
                <li>Wait until all Pass 1 sheets finish printing.</li>
                <li>Take the printed stack from the output tray.</li>
                <li>
                  Flip the stack over (printed side facing away/back depending on your rear input
                  tray) and re-insert into the paper tray.
                </li>
                <li>
                  Click <strong>Print Pass 2</strong> below to print the{" "}
                  {manualDuplexModal.pass2Paths.length} reverse side(s)
                  {manualDuplexModal.addedBlankBack ? " (includes blank back sheet)" : ""}.
                </li>
              </ol>
              {manualDuplexModal.addedBlankBack && (
                <p className="text-[11px] font-medium text-indigo-700 dark:text-indigo-300 bg-white/70 dark:bg-slate-900/50 rounded-lg px-2.5 py-2 border border-indigo-200/70 dark:border-indigo-700/60">
                  An automatic blank back page was added for the final odd sheet—reload the entire
                  stack as-is without removing any pages.
                </p>
              )}
            </div>
            <div className="flex justify-end gap-2">
              <button
                onClick={() => setManualDuplexModal(null)}
                className="px-4 py-2 rounded-xl text-xs font-medium border border-slate-200 dark:border-slate-700"
              >
                Cancel
              </button>
              <button
                onClick={handleManualDuplexPass2}
                disabled={printing}
                className="inline-flex items-center gap-1.5 px-4 py-2 rounded-xl text-xs font-semibold bg-indigo-600 text-white hover:bg-indigo-700"
              >
                <RotateCw className="w-3.5 h-3.5" />
                {printing ? "Printing..." : "Print Pass 2 (Even Pages)"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
