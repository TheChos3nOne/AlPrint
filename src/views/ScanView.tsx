import { useMemo, useState } from "react";
import {
  ArrowLeft,
  ArrowRight,
  BookOpen,
  Check,
  Copy,
  CreditCard,
  Edit3,
  FilePlus2,
  FileText,
  FolderInput,
  Image as ImageIcon,
  Layers,
  Plus,
  Printer,
  RotateCw,
  ScanLine,
  SearchCheck,
  Sparkles,
  Trash2,
  Wifi,
  X,
} from "lucide-react";
import { PageEditorModal, type EditorTab } from "../components/PageEditorModal";
import {
  api,
  pickFiles,
  pickFolder,
  pickSavePdfPath,
  pickSaveTextPath,
  toAssetUrl,
} from "../lib/api";
import { buildPdfBase64, rasterizePdfFile } from "../lib/pdfTools";
import type { OcrResult, ScanPage, ScanRequest, ScannerInfo } from "../types";

interface ScanViewProps {
  sessionId: string;
  scanners: ScannerInfo[];
  selectedScannerId: string;
  onSelectScanner: (id: string) => void;
  onRefreshScanners: () => void;
  pages: ScanPage[];
  onSetPages: React.Dispatch<React.SetStateAction<ScanPage[]>>;
  onSendToPrint: (pages: ScanPage[]) => void;
  onNotify: (msg: string, isError?: boolean) => void;
  onActivityUpdated: () => void;
}

type ScanPreset = "document" | "photo" | "id_card" | "multi_item" | "book_spread";

const PRESET_DESCRIPTIONS: Record<ScanPreset, string> = {
  document: "Standard single-page scan for text, forms, and certificates.",
  photo: "Defaults to 300 DPI Color for sharper photo reproduction.",
  id_card: "Guided 2-step scan that merges front and back onto one page.",
  multi_item: "Place several receipts or photos at once to auto-split into separate pages.",
  book_spread: "Splits two facing open pages down the center spine into 2 pages.",
};

export function ScanView({
  sessionId,
  scanners,
  selectedScannerId,
  onSelectScanner,
  onRefreshScanners,
  pages,
  onSetPages,
  onSendToPrint,
  onNotify,
  onActivityUpdated,
}: ScanViewProps) {
  const activeScanner = scanners.find((s) => s.id === selectedScannerId) ?? scanners[0];

  const [inputMode, setInputMode] = useState<"scanner" | "import">("scanner");
  const [preset, setPreset] = useState<ScanPreset>("document");
  const [source, setSource] = useState<"flatbed" | "adf">("flatbed");
  const [pageSize, setPageSize] = useState<"full" | "a4" | "letter" | "4x6" | "5x7">("full");
  const [colorMode, setColorMode] = useState<"Color" | "Grayscale" | "Black & White">("Color");
  const [dpi, setDpi] = useState<number>(200);
  const [brightness, setBrightness] = useState<number>(0);
  const [contrast, setContrast] = useState<number>(0);
  const [detectEdges, setDetectEdges] = useState<boolean>(false);
  const [autoEnhance, setAutoEnhance] = useState<boolean>(false);

  const [scanning, setScanning] = useState(false);
  const [importing, setImporting] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [rotatingPageId, setRotatingPageId] = useState<string | null>(null);
  const [optimisticRotateDeg, setOptimisticRotateDeg] = useState<number>(0);

  // ID Card two-step state
  const [idFrontPage, setIdFrontPage] = useState<ScanPage | null>(null);

  // Active selection & editing
  const [selectedPageId, setSelectedPageId] = useState<string | null>(null);
  const [editorState, setEditorState] = useState<{
    page: ScanPage;
    initialTab: EditorTab;
    postScanCropMode: boolean;
  } | null>(null);
  const [cacheBust, setCacheBust] = useState<number>(Date.now());
  const [dragIndex, setDragIndex] = useState<number | null>(null);

  // OCR state
  const [ocrRunning, setOcrRunning] = useState(false);
  const [ocrMap, setOcrMap] = useState<Record<string, OcrResult>>({});
  const [ocrDrawerOpen, setOcrDrawerOpen] = useState(false);
  const [searchablePdf, setSearchablePdf] = useState(true);

  // Export image options modal
  const [imgExportModal, setImgExportModal] = useState(false);
  const [imgFormat, setImgFormat] = useState<"jpg" | "png">("jpg");
  const [imgCompression, setImgCompression] = useState<"low" | "medium" | "high">("low");
  const [exportBaseName, setExportBaseName] = useState("Scan");

  // Add eSCL scanner modal
  const [esclModal, setEsclModal] = useState(false);
  const [esclUrl, setEsclUrl] = useState("http://192.168.1.");
  const [esclName, setEsclName] = useState("");
  const [addingEscl, setAddingEscl] = useState(false);

  const selectedPage = useMemo(
    () => pages.find((p) => p.id === selectedPageId) ?? pages[0] ?? null,
    [pages, selectedPageId]
  );

  const availableDpis = activeScanner?.supported_dpis?.length
    ? activeScanner.supported_dpis
    : [75, 100, 150, 200, 300, 600];

  const handleScan = async () => {
    if (!activeScanner) {
      onNotify("No scanner detected. Connect via USB or add a Wi-Fi eSCL scanner.", true);
      return;
    }
    setScanning(true);
    try {
      const req: ScanRequest = {
        session_id: sessionId,
        scanner_id: activeScanner.id,
        backend: activeScanner.backend,
        endpoint: activeScanner.endpoint,
        source: activeScanner.has_adf ? source : "flatbed",
        page_size: pageSize,
        color_mode: colorMode,
        dpi,
        brightness,
        contrast,
        detect_edges: preset === "id_card" ? true : detectEdges,
        auto_enhance: autoEnhance,
        preset,
      };
      const scanned = await api.scanPage(req);

      if (preset === "id_card") {
        const first = scanned[0];
        if (!first) return;
        if (!idFrontPage) {
          setIdFrontPage(first);
          onNotify("Front of ID captured! Flip the ID card on the glass and click 'Scan Back'.");
        } else {
          const stitched = await api.stitchIdCardPages(
            sessionId,
            idFrontPage.edited_path,
            first.edited_path
          );
          setIdFrontPage(null);
          onSetPages((prev) => [...prev, stitched]);
          setSelectedPageId(stitched.id);
          onNotify("ID Card front & back merged onto one page!");
        }
      } else {
        onSetPages((prev) => [...prev, ...scanned]);
        if (scanned[0]) setSelectedPageId(scanned[0].id);
        if (
          detectEdges &&
          (preset === "document" || preset === "photo") &&
          scanned.length === 1
        ) {
          setEditorState({
            page: scanned[0],
            initialTab: "crop",
            postScanCropMode: true,
          });
          onNotify("Scan captured — adjust the crop box and click Apply Crop.");
        } else {
          onNotify(
            scanned.length > 1
              ? `Captured and split into ${scanned.length} separate pages!`
              : "Page scanned successfully."
          );
        }
      }
      setCacheBust(Date.now());
      onActivityUpdated();
    } catch (e) {
      onNotify(String(e), true);
    } finally {
      setScanning(false);
    }
  };

  const handleImportFiles = async () => {
    const paths = await pickFiles({
      multiple: true,
      filters: [
        {
          name: "Documents & Images",
          extensions: ["pdf", "png", "jpg", "jpeg", "bmp", "webp", "tif", "tiff"],
        },
      ],
    });
    if (paths.length === 0) return;

    setImporting(true);
    try {
      const pdfPaths = paths.filter((p) => p.toLowerCase().endsWith(".pdf"));
      const imgPaths = paths.filter((p) => !p.toLowerCase().endsWith(".pdf"));
      const imported: ScanPage[] = [];

      if (imgPaths.length > 0) {
        const res = await api.importImageFiles(sessionId, imgPaths);
        imported.push(...res);
      }

      for (const pdfPath of pdfPaths) {
        const fileName = pdfPath.split(/[\\/]/).pop() ?? "PDF";
        const rendered = await rasterizePdfFile(pdfPath, 2.0);
        for (const r of rendered) {
          const saved = await api.saveBase64Page(
            sessionId,
            r.dataUrl,
            `${fileName} (p.${r.pageNumber})`,
            150
          );
          imported.push(saved);
        }
      }

      onSetPages((prev) => [...prev, ...imported]);
      if (imported[0]) setSelectedPageId(imported[0].id);
      setCacheBust(Date.now());
      onNotify(`Imported ${imported.length} page(s).`);
    } catch (e) {
      onNotify(String(e), true);
    } finally {
      setImporting(false);
    }
  };

  const handleQuickRotate = async (page: ScanPage) => {
    if (rotatingPageId) return;
    setRotatingPageId(page.id);
    setOptimisticRotateDeg(90);
    try {
      // Wait for the smooth 180ms 90° CSS rotation animation + backend PNG rotation together
      const rotatedCrop = page.edits.crop
        ? {
            x: Math.max(0, Math.min(1, 1 - (page.edits.crop.y + page.edits.crop.h))),
            y: Math.max(0, Math.min(1, page.edits.crop.x)),
            w: page.edits.crop.h,
            h: page.edits.crop.w,
          }
        : null;
      const [updated] = await Promise.all([
        api.updatePageEdits(page, {
          ...page.edits,
          rotate_deg: (page.edits.rotate_deg + 90) % 360,
          crop: rotatedCrop,
        }),
        new Promise((resolve) => setTimeout(resolve, 180)),
      ]);
      onSetPages((prev) => prev.map((p) => (p.id === page.id ? updated : p)));
      setCacheBust(Date.now());
    } catch (e) {
      setOptimisticRotateDeg(0);
      setRotatingPageId(null);
      onNotify(String(e), true);
    }
  };

  const handleDeletePage = (id: string) => {
    onSetPages((prev) => {
      const next = prev.filter((p) => p.id !== id);
      if (selectedPageId === id) {
        setSelectedPageId(next[0]?.id ?? null);
      }
      return next;
    });
  };

  const movePage = (index: number, delta: number) => {
    const target = index + delta;
    if (target < 0 || target >= pages.length) return;
    onSetPages((prev) => {
      const copy = [...prev];
      const [moved] = copy.splice(index, 1);
      copy.splice(target, 0, moved);
      return copy;
    });
  };

  const handleDropReorder = (dropIndex: number) => {
    if (dragIndex === null || dragIndex === dropIndex) return;
    onSetPages((prev) => {
      const copy = [...prev];
      const [moved] = copy.splice(dragIndex, 1);
      copy.splice(dropIndex, 0, moved);
      return copy;
    });
    setDragIndex(null);
  };

  const handleRunOcrAll = async () => {
    if (pages.length === 0) return;
    setOcrRunning(true);
    try {
      const nextMap: Record<string, OcrResult> = { ...ocrMap };
      for (const p of pages) {
        if (!nextMap[p.id]) {
          nextMap[p.id] = await api.runOcr(p.edited_path);
        }
      }
      setOcrMap(nextMap);
      const firstOcr = nextMap[pages[0].id];
      if (firstOcr?.suggested_filename) {
        setExportBaseName(firstOcr.suggested_filename);
      }
      setOcrDrawerOpen(true);
      onNotify(`Offline OCR completed across ${pages.length} page(s).`);
    } catch (e) {
      onNotify(String(e), true);
    } finally {
      setOcrRunning(false);
    }
  };

  const handleSavePdf = async () => {
    if (pages.length === 0) return;
    const targetPath = await pickSavePdfPath(exportBaseName || "Scan");
    if (!targetPath) return;

    setExporting(true);
    try {
      let currentOcrMap = { ...ocrMap };
      if (searchablePdf) {
        for (const p of pages) {
          if (!currentOcrMap[p.id]) {
            currentOcrMap[p.id] = await api.runOcr(p.edited_path);
          }
        }
        setOcrMap(currentOcrMap);
      }
      const b64 = await buildPdfBase64(pages, searchablePdf ? currentOcrMap : undefined);
      const savedPath = await api.savePdfBytes(targetPath, b64);
      onActivityUpdated();
      onNotify(`Saved ${searchablePdf ? "Searchable " : ""}PDF to ${savedPath}`);
    } catch (e) {
      onNotify(String(e), true);
    } finally {
      setExporting(false);
    }
  };

  const handleExportImages = async () => {
    if (pages.length === 0) return;
    const folder = await pickFolder();
    if (!folder) return;

    setExporting(true);
    try {
      const written = await api.exportImages(
        pages.map((p) => p.edited_path),
        folder,
        imgFormat,
        imgCompression,
        exportBaseName || "Scan"
      );
      setImgExportModal(false);
      onActivityUpdated();
      onNotify(`Exported ${written.length} ${imgFormat.toUpperCase()} image(s) to ${folder}`);
    } catch (e) {
      onNotify(String(e), true);
    } finally {
      setExporting(false);
    }
  };

  const handleAddEscl = async () => {
    if (!esclUrl.trim()) return;
    setAddingEscl(true);
    try {
      const added = await api.addEsclScanner(esclUrl, esclName || undefined);
      setEsclModal(false);
      setEsclName("");
      onRefreshScanners();
      onSelectScanner(added.id);
      onNotify(`Added network scanner: ${added.name}`);
    } catch (e) {
      onNotify(String(e), true);
    } finally {
      setAddingEscl(false);
    }
  };

  const combinedOcrText = useMemo(
    () =>
      pages
        .map((p, idx) => {
          const res = ocrMap[p.id];
          if (!res) return "";
          return pages.length > 1 ? `--- Page ${idx + 1} ---\n${res.full_text}` : res.full_text;
        })
        .filter(Boolean)
        .join("\n\n"),
    [pages, ocrMap]
  );

  return (
    <div className="h-full flex overflow-hidden">
      {/* Left Scan / Import Controls */}
      <div className="w-80 shrink-0 border-r border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 flex flex-col">
        <div className="p-3 border-b border-slate-100 dark:border-slate-800">
          <div className="grid grid-cols-2 rounded-xl bg-slate-100 dark:bg-slate-800 p-1">
            <button
              onClick={() => setInputMode("scanner")}
              className={`py-1.5 rounded-lg text-xs font-semibold transition ${
                inputMode === "scanner"
                  ? "bg-white dark:bg-slate-700 text-indigo-600 dark:text-indigo-300 shadow-sm"
                  : "text-slate-600 dark:text-slate-400"
              }`}
            >
              Scanner
            </button>
            <button
              onClick={() => setInputMode("import")}
              className={`py-1.5 rounded-lg text-xs font-semibold transition ${
                inputMode === "import"
                  ? "bg-white dark:bg-slate-700 text-indigo-600 dark:text-indigo-300 shadow-sm"
                  : "text-slate-600 dark:text-slate-400"
              }`}
            >
              Import Files
            </button>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto p-4 space-y-4">
          {inputMode === "scanner" ? (
            <>
              <div>
                <div className="flex items-center justify-between mb-1">
                  <label className="text-xs font-semibold text-slate-600 dark:text-slate-300">
                    Scanner Device
                  </label>
                  <button
                    onClick={() => setEsclModal(true)}
                    className="text-[11px] font-medium text-indigo-600 dark:text-indigo-400 hover:underline flex items-center gap-1"
                  >
                    <Wifi className="w-3 h-3" /> Add Wi-Fi
                  </button>
                </div>
                <select
                  value={activeScanner?.id ?? ""}
                  onChange={(e) => onSelectScanner(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 text-xs font-medium"
                >
                  {scanners.length === 0 && <option value="">No scanner found</option>}
                  {scanners.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label className="text-xs font-semibold text-slate-600 dark:text-slate-300 block mb-1.5">
                  Scan Preset
                </label>
                <div className="grid grid-cols-2 gap-1.5">
                  {(
                    [
                      { id: "document", label: "Document", icon: FileText },
                      { id: "photo", label: "Photo", icon: ImageIcon },
                      { id: "id_card", label: "ID Card", icon: CreditCard },
                      { id: "multi_item", label: "Multi-Item", icon: Layers },
                      { id: "book_spread", label: "Book Spread", icon: BookOpen },
                    ] as const
                  ).map((p) => {
                    const Icon = p.icon;
                    return (
                      <button
                        key={p.id}
                        onClick={() => {
                          setPreset(p.id);
                          setIdFrontPage(null);
                          if (p.id === "photo") {
                            setDpi(availableDpis.includes(300) ? 300 : availableDpis[0]);
                            setColorMode("Color");
                          }
                        }}
                        className={`flex items-center gap-1.5 px-2.5 py-2 rounded-lg text-xs font-medium border transition ${
                          preset === p.id
                            ? "border-indigo-500 bg-indigo-50 dark:bg-indigo-500/20 text-indigo-700 dark:text-indigo-300"
                            : "border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800"
                        }`}
                      >
                        <Icon className="w-3.5 h-3.5 shrink-0" />
                        <span>{p.label}</span>
                      </button>
                    );
                  })}
                </div>
                <p className="text-[11px] text-slate-500 dark:text-slate-400 mt-1.5 leading-snug">
                  {PRESET_DESCRIPTIONS[preset]}
                </p>
              </div>

              <div className="grid grid-cols-2 gap-2.5">
                <div>
                  <label className="text-xs font-medium text-slate-500 block mb-1">Source</label>
                  <select
                    value={activeScanner?.has_adf ? source : "flatbed"}
                    onChange={(e) => setSource(e.target.value as "flatbed" | "adf")}
                    className="w-full px-2.5 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 text-xs"
                  >
                    <option value="flatbed">Scanner Glass</option>
                    <option value="adf" disabled={!activeScanner?.has_adf}>
                      {activeScanner?.has_adf ? "Document Feeder" : "Feeder (N/A)"}
                    </option>
                  </select>
                </div>

                <div>
                  <label className="text-xs font-medium text-slate-500 block mb-1">Page Size</label>
                  <select
                    value={pageSize}
                    onChange={(e) =>
                      setPageSize(e.target.value as "full" | "a4" | "letter" | "4x6" | "5x7")
                    }
                    className="w-full px-2.5 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 text-xs"
                  >
                    <option value="full">Entire Scan Area</option>
                    <option value="a4">A4 (210 × 297 mm)</option>
                    <option value="letter">Letter (8.5 × 11")</option>
                    <option value="4x6">Photo 4 × 6"</option>
                    <option value="5x7">Photo 5 × 7"</option>
                  </select>
                </div>

                <div>
                  <label className="text-xs font-medium text-slate-500 block mb-1">
                    Color Mode
                  </label>
                  <select
                    value={colorMode}
                    onChange={(e) =>
                      setColorMode(e.target.value as "Color" | "Grayscale" | "Black & White")
                    }
                    className="w-full px-2.5 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 text-xs"
                  >
                    {(activeScanner?.color_modes ?? ["Color", "Grayscale", "Black & White"]).map(
                      (m) => (
                        <option key={m} value={m}>
                          {m}
                        </option>
                      )
                    )}
                  </select>
                </div>

                <div>
                  <label className="text-xs font-medium text-slate-500 block mb-1">
                    Resolution
                  </label>
                  <select
                    value={dpi}
                    onChange={(e) => setDpi(Number(e.target.value))}
                    className="w-full px-2.5 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 text-xs"
                  >
                    {availableDpis.map((d) => (
                      <option key={d} value={d}>
                        {d} DPI
                      </option>
                    ))}
                  </select>
                </div>
              </div>

              <div className="space-y-2.5 pt-1">
                <div>
                  <div className="flex justify-between text-xs mb-1">
                    <span className="text-slate-600 dark:text-slate-300">Brightness</span>
                    <span className="text-slate-400">{brightness}</span>
                  </div>
                  <input
                    type="range"
                    min={-100}
                    max={100}
                    value={brightness}
                    onChange={(e) => setBrightness(Number(e.target.value))}
                    className="w-full accent-indigo-600"
                  />
                </div>
                <div>
                  <div className="flex justify-between text-xs mb-1">
                    <span className="text-slate-600 dark:text-slate-300">Contrast</span>
                    <span className="text-slate-400">{contrast}</span>
                  </div>
                  <input
                    type="range"
                    min={-100}
                    max={100}
                    value={contrast}
                    onChange={(e) => setContrast(Number(e.target.value))}
                    className="w-full accent-indigo-600"
                  />
                </div>
              </div>

              <div className="space-y-2 pt-1">
                <label className="flex items-center justify-between text-xs font-medium text-slate-700 dark:text-slate-300 cursor-pointer">
                  <span>Detect Edges (Crop Box)</span>
                  <input
                    type="checkbox"
                    checked={detectEdges}
                    onChange={(e) => setDetectEdges(e.target.checked)}
                    className="rounded accent-indigo-600 w-4 h-4"
                  />
                </label>
                <label className="flex items-center justify-between text-xs font-medium text-slate-700 dark:text-slate-300 cursor-pointer">
                  <span>Auto-Enhance (Clean White)</span>
                  <input
                    type="checkbox"
                    checked={autoEnhance}
                    onChange={(e) => setAutoEnhance(e.target.checked)}
                    className="rounded accent-indigo-600 w-4 h-4"
                  />
                </label>
              </div>
            </>
          ) : (
            <div className="space-y-4">
              <div className="rounded-xl border-2 border-dashed border-slate-300 dark:border-slate-700 p-6 text-center space-y-3">
                <FolderInput className="w-9 h-9 mx-auto text-indigo-500" />
                <div>
                  <h3 className="text-sm font-semibold text-slate-800 dark:text-slate-200">
                    Import Local Files
                  </h3>
                  <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
                    Import PDFs, JPEGs, PNGs, BMPs, or TIFFs to crop, annotate, reorder, or run OCR.
                  </p>
                </div>
                <button
                  onClick={handleImportFiles}
                  disabled={importing}
                  className="w-full py-2.5 px-4 rounded-xl bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-semibold"
                >
                  {importing ? "Importing..." : "Choose Files from PC"}
                </button>
              </div>
            </div>
          )}
        </div>

        {inputMode === "scanner" && (
          <div className="p-4 border-t border-slate-100 dark:border-slate-800 space-y-2">
            {preset === "id_card" && (
              <div className="rounded-xl bg-sky-50 dark:bg-sky-500/15 border border-sky-200 dark:border-sky-800 p-2.5 text-xs text-sky-800 dark:text-sky-200 flex items-center justify-between">
                <span>
                  {idFrontPage
                    ? "Step 2: Flip ID card on glass"
                    : "Step 1: Place ID front on glass"}
                </span>
                {idFrontPage && (
                  <button
                    onClick={() => setIdFrontPage(null)}
                    className="underline text-[11px]"
                  >
                    Reset
                  </button>
                )}
              </div>
            )}
            <button
              onClick={handleScan}
              disabled={scanning || !activeScanner}
              className="w-full flex items-center justify-center gap-2 py-3 px-4 rounded-xl bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50 text-white text-sm font-semibold shadow-sm transition"
            >
              {preset === "id_card" ? (
                <>
                  <CreditCard className="w-4 h-4" />
                  {scanning
                    ? "Scanning..."
                    : idFrontPage
                    ? "Scan Back & Merge"
                    : "Scan Front of ID"}
                </>
              ) : (
                <>
                  {pages.length > 0 ? <Plus className="w-4 h-4" /> : <ScanLine className="w-4 h-4" />}
                  {scanning
                    ? "Scanning..."
                    : pages.length > 0
                    ? "Scan Next Page"
                    : "Scan"}
                </>
              )}
            </button>
          </div>
        )}
      </div>

      {/* Main Workspace & Export Bar */}
      <div className="flex-1 flex flex-col min-w-0 bg-slate-50 dark:bg-slate-950">
        {/* Top Action Bar */}
        <div className="px-5 py-3 bg-white dark:bg-slate-900 border-b border-slate-200 dark:border-slate-800 flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <span className="text-sm font-semibold text-slate-800 dark:text-slate-100">
              Scan Session ({pages.length} {pages.length === 1 ? "page" : "pages"})
            </span>
            <button
              onClick={handleImportFiles}
              disabled={importing}
              className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium border border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800"
            >
              <FilePlus2 className="w-3.5 h-3.5" /> Add File
            </button>
            {pages.length > 0 && (
              <button
                onClick={() => {
                  onSetPages([]);
                  setSelectedPageId(null);
                  setOcrMap({});
                }}
                className="text-xs text-slate-500 hover:text-red-600 px-2 py-1"
              >
                Clear All
              </button>
            )}
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <button
              onClick={handleRunOcrAll}
              disabled={pages.length === 0 || ocrRunning}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-semibold border border-indigo-200 dark:border-indigo-800 bg-indigo-50/70 dark:bg-indigo-500/15 text-indigo-700 dark:text-indigo-300 hover:bg-indigo-100 disabled:opacity-40"
            >
              <SearchCheck className="w-3.5 h-3.5" />
              {ocrRunning ? "Recognizing Text..." : "Offline OCR"}
            </button>

            <label className="inline-flex items-center gap-1.5 text-xs text-slate-600 dark:text-slate-300 px-2 cursor-pointer">
              <input
                type="checkbox"
                checked={searchablePdf}
                onChange={(e) => setSearchablePdf(e.target.checked)}
                className="rounded accent-indigo-600"
              />
              Searchable PDF
            </label>

            <button
              onClick={handleSavePdf}
              disabled={pages.length === 0 || exporting}
              className="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-xl text-xs font-semibold bg-indigo-600 hover:bg-indigo-700 text-white disabled:opacity-40"
            >
              <FileText className="w-3.5 h-3.5" />
              {exporting ? "Saving..." : "Save PDF"}
            </button>

            <button
              onClick={() => setImgExportModal(true)}
              disabled={pages.length === 0 || exporting}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-semibold border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 hover:bg-slate-50 disabled:opacity-40"
            >
              <ImageIcon className="w-3.5 h-3.5" /> Save Images
            </button>

            <button
              onClick={() => onSendToPrint(pages)}
              disabled={pages.length === 0}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-semibold bg-emerald-600 hover:bg-emerald-700 text-white disabled:opacity-40"
            >
              <Printer className="w-3.5 h-3.5" /> Print
            </button>
          </div>
        </div>

        {/* Center Preview + OCR Drawer */}
        <div className="flex-1 min-h-0 flex">
          <div className="flex-1 flex flex-col min-w-0">
            {pages.length === 0 ? (
              <div className="flex-1 flex flex-col items-center justify-center p-8 text-center">
                <div className="w-16 h-16 rounded-2xl bg-indigo-50 dark:bg-indigo-500/15 text-indigo-500 flex items-center justify-center mb-4">
                  <ScanLine className="w-8 h-8" />
                </div>
                <h3 className="text-base font-semibold text-slate-800 dark:text-slate-100">
                  Ready to Scan or Import
                </h3>
                <p className="text-xs text-slate-500 dark:text-slate-400 max-w-md mt-1.5 leading-relaxed">
                  Place a document, photo, receipt group, book spread, or ID card on your{" "}
                  <span className="font-medium text-slate-700 dark:text-slate-300">
                    {activeScanner?.name ?? "scanner"}
                  </span>{" "}
                  and click <strong>Scan</strong>, or import existing PDFs and images to edit &amp;
                  OCR.
                </p>
              </div>
            ) : (
              <>
                {/* Large Active Preview */}
                <div className="flex-1 min-h-0 p-6 flex flex-col items-center justify-center overflow-hidden relative">
                  {selectedPage && (
                    <>
                      <div className="flex-1 min-h-0 w-full flex items-center justify-center overflow-hidden">
                        <img
                          src={toAssetUrl(selectedPage.edited_path, cacheBust)}
                          alt={selectedPage.source_label}
                          onLoad={() => {
                            if (rotatingPageId === selectedPage.id) {
                              setOptimisticRotateDeg(0);
                              setRotatingPageId(null);
                            }
                          }}
                          style={
                            rotatingPageId === selectedPage.id && optimisticRotateDeg !== 0
                              ? {
                                  transform: `rotate(${optimisticRotateDeg}deg)`,
                                  transition: "transform 180ms cubic-bezier(0.22, 1, 0.36, 1)",
                                }
                              : {
                                  transform: "rotate(0deg)",
                                  transition: "none",
                                }
                          }
                          className="max-h-full max-w-full object-contain rounded-xl shadow-lg bg-white"
                        />
                      </div>
                      <div className="mt-3 shrink-0 flex items-center gap-2 bg-white/95 dark:bg-slate-900/95 border border-slate-200 dark:border-slate-700 rounded-full px-4 py-1.5 shadow-sm text-xs">
                        <span className="text-slate-500">
                          {selectedPage.width} × {selectedPage.height}px · {selectedPage.dpi} DPI
                        </span>
                        <span className="text-slate-300">|</span>
                        <button
                          onClick={() => handleQuickRotate(selectedPage)}
                          disabled={rotatingPageId === selectedPage.id}
                          className="inline-flex items-center gap-1 font-medium text-slate-700 dark:text-slate-200 hover:text-indigo-600 disabled:opacity-60"
                        >
                          <RotateCw className="w-3.5 h-3.5 transition-transform duration-200" />
                          Rotate
                        </button>
                        <button
                          onClick={() =>
                            setEditorState({
                              page: selectedPage,
                              initialTab: "adjust",
                              postScanCropMode: false,
                            })
                          }
                          className="inline-flex items-center gap-1 font-medium text-indigo-600 dark:text-indigo-400 hover:underline"
                        >
                          <Edit3 className="w-3.5 h-3.5" /> Crop / Filter / Markup
                        </button>
                      </div>
                    </>
                  )}
                </div>

                {/* Bottom Multi-Page Thumbnail Strip */}
                <div className="h-36 shrink-0 border-t border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 px-4 py-2.5 flex items-center gap-3 overflow-x-auto">
                  {pages.map((p, idx) => {
                    const isActive = selectedPage?.id === p.id;
                    return (
                      <div
                        key={p.id}
                        draggable
                        onDragStart={() => setDragIndex(idx)}
                        onDragOver={(e) => e.preventDefault()}
                        onDrop={() => handleDropReorder(idx)}
                        onClick={() => setSelectedPageId(p.id)}
                        className={`group relative h-full w-24 shrink-0 rounded-xl border-2 p-1 cursor-pointer transition flex flex-col items-center justify-between bg-slate-50 dark:bg-slate-800 ${
                          isActive
                            ? "border-indigo-500 ring-2 ring-indigo-500/20"
                            : "border-slate-200 dark:border-slate-700 hover:border-slate-400"
                        }`}
                      >
                        <img
                          src={toAssetUrl(p.edited_path, cacheBust)}
                          alt={`Page ${idx + 1}`}
                          className="h-20 w-full object-contain rounded"
                        />
                        <div className="w-full flex items-center justify-between px-1 text-[10px] font-semibold text-slate-500">
                          <span>p.{idx + 1}</span>
                          {ocrMap[p.id] && (
                            <span title="OCR text ready" className="text-emerald-600">
                              OCR
                            </span>
                          )}
                        </div>

                        {/* Hover Actions */}
                        <div className="opacity-0 group-hover:opacity-100 transition absolute -top-2 -right-2 flex items-center gap-0.5">
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              handleDeletePage(p.id);
                            }}
                            className="p-1 rounded-full bg-red-600 text-white shadow hover:bg-red-700"
                            title="Delete page"
                          >
                            <Trash2 className="w-3 h-3" />
                          </button>
                        </div>
                        <div className="opacity-0 group-hover:opacity-100 transition absolute bottom-5 inset-x-1 flex justify-between pointer-events-none">
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              movePage(idx, -1);
                            }}
                            disabled={idx === 0}
                            className="pointer-events-auto p-0.5 rounded bg-slate-900/75 text-white disabled:opacity-30"
                          >
                            <ArrowLeft className="w-3 h-3" />
                          </button>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              movePage(idx, 1);
                            }}
                            disabled={idx === pages.length - 1}
                            className="pointer-events-auto p-0.5 rounded bg-slate-900/75 text-white disabled:opacity-30"
                          >
                            <ArrowRight className="w-3 h-3" />
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </>
            )}
          </div>

          {/* OCR Text Side Drawer */}
          {ocrDrawerOpen && (
            <div className="w-80 shrink-0 border-l border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-4 flex flex-col gap-3">
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-semibold flex items-center gap-1.5">
                  <Sparkles className="w-4 h-4 text-indigo-500" /> Extracted OCR Text
                </h3>
                <button
                  onClick={() => setOcrDrawerOpen(false)}
                  className="p-1 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
              <div>
                <label className="text-[11px] font-medium text-slate-500 block mb-1">
                  Suggested Filename
                </label>
                <input
                  type="text"
                  value={exportBaseName}
                  onChange={(e) => setExportBaseName(e.target.value)}
                  className="w-full px-2.5 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 text-xs"
                />
              </div>
              <textarea
                readOnly
                value={combinedOcrText || "(No text recognized)"}
                className="flex-1 w-full p-2.5 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 text-xs font-mono resize-none"
              />
              <div className="grid grid-cols-2 gap-2">
                <button
                  onClick={() => {
                    navigator.clipboard.writeText(combinedOcrText);
                    onNotify("Copied OCR text to clipboard.");
                  }}
                  className="flex items-center justify-center gap-1.5 py-2 rounded-xl border border-slate-200 dark:border-slate-700 text-xs font-medium hover:bg-slate-50 dark:hover:bg-slate-800"
                >
                  <Copy className="w-3.5 h-3.5" /> Copy
                </button>
                <button
                  onClick={async () => {
                    const out = await pickSaveTextPath(exportBaseName || "OCR_Text");
                    if (!out) return;
                    await api.saveTextFile(out, combinedOcrText);
                    onNotify(`Saved text file to ${out}`);
                  }}
                  className="flex items-center justify-center gap-1.5 py-2 rounded-xl bg-indigo-600 text-white text-xs font-semibold hover:bg-indigo-700"
                >
                  <Check className="w-3.5 h-3.5" /> Save .txt
                </button>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Page Editor Modal */}
      {editorState && (
        <PageEditorModal
          page={editorState.page}
          initialTab={editorState.initialTab}
          postScanCropMode={editorState.postScanCropMode}
          onClose={() => setEditorState(null)}
          onSaved={(updated) => {
            onSetPages((prev) => prev.map((p) => (p.id === updated.id ? updated : p)));
            setCacheBust(Date.now());
            onNotify(
              editorState.postScanCropMode ? "Crop applied." : "Page edits applied."
            );
          }}
        />
      )}

      {/* Save Images Options Modal */}
      {imgExportModal && (
        <div className="fixed inset-0 z-50 bg-slate-950/60 flex items-center justify-center p-4">
          <div className="w-full max-w-md rounded-2xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 p-5 space-y-4 shadow-xl">
            <div className="flex items-center justify-between">
              <h3 className="font-semibold">Export Pages as Images</h3>
              <button onClick={() => setImgExportModal(false)}>
                <X className="w-4 h-4" />
              </button>
            </div>
            <div className="space-y-3 text-xs">
              <div>
                <label className="font-medium block mb-1">Base File Name</label>
                <input
                  type="text"
                  value={exportBaseName}
                  onChange={(e) => setExportBaseName(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
                />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="font-medium block mb-1">Format</label>
                  <select
                    value={imgFormat}
                    onChange={(e) => setImgFormat(e.target.value as "jpg" | "png")}
                    className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
                  >
                    <option value="jpg">JPEG (.jpg)</option>
                    <option value="png">PNG (.png)</option>
                  </select>
                </div>
                <div>
                  <label className="font-medium block mb-1">Compression</label>
                  <select
                    value={imgCompression}
                    disabled={imgFormat === "png"}
                    onChange={(e) =>
                      setImgCompression(e.target.value as "low" | "medium" | "high")
                    }
                    className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 disabled:opacity-50"
                  >
                    <option value="low">Low (Best Quality)</option>
                    <option value="medium">Medium (Balanced)</option>
                    <option value="high">High (Smallest File)</option>
                  </select>
                </div>
              </div>
            </div>
            <div className="flex justify-end gap-2 pt-2">
              <button
                onClick={() => setImgExportModal(false)}
                className="px-4 py-2 rounded-xl text-xs font-medium border border-slate-200 dark:border-slate-700"
              >
                Cancel
              </button>
              <button
                onClick={handleExportImages}
                disabled={exporting}
                className="px-4 py-2 rounded-xl text-xs font-semibold bg-indigo-600 text-white hover:bg-indigo-700"
              >
                {exporting ? "Exporting..." : "Choose Folder & Export"}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Add Wi-Fi eSCL Scanner Modal */}
      {esclModal && (
        <div className="fixed inset-0 z-50 bg-slate-950/60 flex items-center justify-center p-4">
          <div className="w-full max-w-md rounded-2xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 p-5 space-y-4 shadow-xl">
            <div className="flex items-center justify-between">
              <h3 className="font-semibold flex items-center gap-2">
                <Wifi className="w-4 h-4 text-indigo-500" /> Add Network Scanner (eSCL / AirScan)
              </h3>
              <button onClick={() => setEsclModal(false)}>
                <X className="w-4 h-4" />
              </button>
            </div>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              Enter your printer&apos;s local IP or base URL (for example{" "}
              <span className="font-mono">http://192.168.1.45</span>). Works driverlessly across HP,
              Canon, Brother, and Epson Wi-Fi scanners.
            </p>
            <div className="space-y-3 text-xs">
              <div>
                <label className="font-medium block mb-1">Scanner URL / IP</label>
                <input
                  type="text"
                  value={esclUrl}
                  onChange={(e) => setEsclUrl(e.target.value)}
                  placeholder="http://192.168.1.45"
                  className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
                />
              </div>
              <div>
                <label className="font-medium block mb-1">Custom Display Name (Optional)</label>
                <input
                  type="text"
                  value={esclName}
                  onChange={(e) => setEsclName(e.target.value)}
                  placeholder="e.g. HP Smart Tank 580 (Wi-Fi)"
                  className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
                />
              </div>
            </div>
            <div className="flex justify-end gap-2">
              <button
                onClick={() => setEsclModal(false)}
                className="px-4 py-2 rounded-xl text-xs font-medium border border-slate-200 dark:border-slate-700"
              >
                Cancel
              </button>
              <button
                onClick={handleAddEscl}
                disabled={addingEscl}
                className="px-4 py-2 rounded-xl text-xs font-semibold bg-indigo-600 text-white hover:bg-indigo-700"
              >
                {addingEscl ? "Connecting..." : "Probe & Save"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
