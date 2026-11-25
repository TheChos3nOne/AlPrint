import { useEffect, useState } from "react";
import {
  Edit3,
  FolderOpen,
  Image as ImageIcon,
  Printer,
  RotateCw,
  Trash2,
} from "lucide-react";
import { PageEditorModal } from "../components/PageEditorModal";
import { api, pickFiles, toAssetUrl } from "../lib/api";
import type { PrinterInfo, ScanPage } from "../types";

interface PrintPhotosViewProps {
  sessionId: string;
  printers: PrinterInfo[];
  selectedPrinterName: string;
  onSelectPrinter: (name: string) => void;
  onNotify: (msg: string, isError?: boolean) => void;
  onActivityUpdated: () => void;
}

const PHOTO_PRESETS = [
  { id: "4x6", label: '4 × 6"', aspect: 4 / 6, paperMatch: ["4x6", "4 x 6", "10x15"] },
  { id: "5x7", label: '5 × 7"', aspect: 5 / 7, paperMatch: ["5x7", "5 x 7", "13x18"] },
  { id: "8x10", label: '8 × 10"', aspect: 8 / 10, paperMatch: ["8x10", "8 x 10"] },
  { id: "a4", label: "A4", aspect: 210 / 297, paperMatch: ["a4"] },
  { id: "letter", label: "Letter", aspect: 8.5 / 11, paperMatch: ["letter"] },
];

export function PrintPhotosView({
  sessionId,
  printers,
  selectedPrinterName,
  onSelectPrinter,
  onNotify,
  onActivityUpdated,
}: PrintPhotosViewProps) {
  const activePrinter = printers.find((p) => p.name === selectedPrinterName) ?? printers[0];

  const [photos, setPhotos] = useState<ScanPage[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [editingPhoto, setEditingPhoto] = useState<ScanPage | null>(null);
  const [cacheBust, setCacheBust] = useState(Date.now());

  const [sizePresetId, setSizePresetId] = useState("4x6");
  const [paperSizeName, setPaperSizeName] = useState("A4");
  const [orientation, setOrientation] = useState<"portrait" | "landscape">("portrait");
  const [borderless, setBorderless] = useState(true);
  const [copies, setCopies] = useState(1);
  const [printing, setPrinting] = useState(false);
  const [rotatingId, setRotatingId] = useState<string | null>(null);
  const [optimisticDeg, setOptimisticDeg] = useState(0);

  const activePhoto = photos.find((p) => p.id === activeId) ?? photos[0] ?? null;
  const activePreset = PHOTO_PRESETS.find((p) => p.id === sizePresetId) ?? PHOTO_PRESETS[0];

  useEffect(() => {
    if (!activePrinter?.paper_sizes?.length) return;
    const match = activePrinter.paper_sizes.find((sz) =>
      activePreset.paperMatch.some((m) => sz.name.toLowerCase().includes(m))
    );
    if (match) {
      setPaperSizeName(match.name);
    } else if (!activePrinter.paper_sizes.some((s) => s.name === paperSizeName)) {
      setPaperSizeName(activePrinter.paper_sizes[0].name);
    }
  }, [activePrinter, activePreset, paperSizeName]);

  const handlePickPhotos = async () => {
    const paths = await pickFiles({
      multiple: true,
      filters: [
        { name: "Photos", extensions: ["jpg", "jpeg", "png", "bmp", "webp", "tif", "tiff"] },
      ],
    });
    if (paths.length === 0) return;

    try {
      const imported = await api.importImageFiles(sessionId, paths);
      setPhotos((prev) => [...prev, ...imported]);
      if (imported[0]) {
        setActiveId(imported[0].id);
        if (imported[0].width > imported[0].height) setOrientation("landscape");
      }
      setCacheBust(Date.now());
    } catch (e) {
      onNotify(String(e), true);
    }
  };

  const handleQuickRotate = async (photo: ScanPage) => {
    if (rotatingId) return;
    setRotatingId(photo.id);
    setOptimisticDeg(90);
    try {
      const [updated] = await Promise.all([
        api.updatePageEdits(photo, {
          ...photo.edits,
          rotate_deg: (photo.edits.rotate_deg + 90) % 360,
        }),
        new Promise((resolve) => setTimeout(resolve, 180)),
      ]);
      setPhotos((prev) => prev.map((p) => (p.id === photo.id ? updated : p)));
      setCacheBust(Date.now());
    } catch (e) {
      setOptimisticDeg(0);
      setRotatingId(null);
      onNotify(String(e), true);
    }
  };

  const handlePrintPhotos = async () => {
    if (!activePrinter || photos.length === 0) return;
    setPrinting(true);
    try {
      const msg = await api.printPages({
        printer_name: activePrinter.name,
        page_paths: photos.map((p) => p.edited_path),
        copies,
        landscape: orientation === "landscape",
        paper_size_name: paperSizeName,
        color: true,
        duplex: "simplex",
        borderless,
        fit_mode: borderless ? "fill" : "fit",
        job_name: "AlPrint Photo Print",
        margin_mm: borderless ? 0 : 8,
        scale_percent: 100,
      });
      onActivityUpdated();
      onNotify(msg);
    } catch (e) {
      onNotify(String(e), true);
    } finally {
      setPrinting(false);
    }
  };

  const selectedDriverPaper = activePrinter?.paper_sizes?.find((s) => s.name === paperSizeName);
  const portraitPaperAspect =
    selectedDriverPaper && selectedDriverPaper.width_mm > 0 && selectedDriverPaper.height_mm > 0
      ? selectedDriverPaper.width_mm / selectedDriverPaper.height_mm
      : activePreset.aspect;
  const frameAspect =
    orientation === "landscape" ? 1 / portraitPaperAspect : portraitPaperAspect;

  return (
    <div className="h-full flex overflow-hidden">
      {/* Left Controls */}
      <div className="w-80 shrink-0 border-r border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 flex flex-col">
        <div className="p-4 border-b border-slate-100 dark:border-slate-800 flex items-center justify-between">
          <h2 className="text-sm font-semibold flex items-center gap-2">
            <ImageIcon className="w-4 h-4 text-pink-500" /> Photo Settings
          </h2>
          <button
            onClick={handlePickPhotos}
            className="text-xs font-medium text-indigo-600 dark:text-indigo-400 hover:underline flex items-center gap-1"
          >
            <FolderOpen className="w-3.5 h-3.5" /> Add Photos
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

          <div>
            <label className="font-medium text-slate-500 block mb-1.5">Photo Layout Size</label>
            <div className="grid grid-cols-3 gap-1.5">
              {PHOTO_PRESETS.map((sz) => (
                <button
                  key={sz.id}
                  onClick={() => setSizePresetId(sz.id)}
                  className={`py-2 rounded-xl border font-semibold transition ${
                    sizePresetId === sz.id
                      ? "border-indigo-500 bg-indigo-50 dark:bg-indigo-500/20 text-indigo-700 dark:text-indigo-300"
                      : "border-slate-200 dark:border-slate-700"
                  }`}
                >
                  {sz.label}
                </button>
              ))}
            </div>
          </div>

          <div>
            <label className="font-medium text-slate-500 block mb-1">Driver Paper Size</label>
            <select
              value={paperSizeName}
              onChange={(e) => setPaperSizeName(e.target.value)}
              className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
            >
              {(activePrinter?.paper_sizes ?? [{ name: "4x6 in" }, { name: "A4" }]).map((s) => (
                <option key={s.name} value={s.name}>
                  {s.name}
                </option>
              ))}
            </select>
          </div>

          <div className="grid grid-cols-2 gap-2.5">
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
            <div>
              <label className="font-medium text-slate-500 block mb-1">Copies Each</label>
              <input
                type="number"
                min={1}
                max={99}
                value={copies}
                onChange={(e) => setCopies(Math.max(1, Number(e.target.value)))}
                className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800"
              />
            </div>
          </div>

          <div>
            <label className="font-medium text-slate-500 block mb-1.5">Framing Mode</label>
            <div className="grid grid-cols-2 gap-2">
              <button
                onClick={() => setBorderless(true)}
                className={`py-2 px-3 rounded-xl border font-medium ${
                  borderless
                    ? "border-indigo-500 bg-indigo-50 dark:bg-indigo-500/20 text-indigo-700 dark:text-indigo-300"
                    : "border-slate-200 dark:border-slate-700"
                }`}
              >
                Borderless (Fill)
              </button>
              <button
                onClick={() => setBorderless(false)}
                className={`py-2 px-3 rounded-xl border font-medium ${
                  !borderless
                    ? "border-indigo-500 bg-indigo-50 dark:bg-indigo-500/20 text-indigo-700 dark:text-indigo-300"
                    : "border-slate-200 dark:border-slate-700"
                }`}
              >
                Bordered (Fit)
              </button>
            </div>
          </div>
        </div>

        <div className="p-4 border-t border-slate-100 dark:border-slate-800">
          <button
            onClick={handlePrintPhotos}
            disabled={printing || photos.length === 0 || !activePrinter}
            className="w-full flex items-center justify-center gap-2 py-3 px-4 rounded-xl bg-indigo-600 hover:bg-indigo-700 disabled:opacity-40 text-white text-sm font-semibold shadow-sm"
          >
            <Printer className="w-4 h-4" />
            {printing
              ? "Printing Photos..."
              : `Print ${photos.length} Photo${photos.length === 1 ? "" : "s"}`}
          </button>
        </div>
      </div>

      {/* Preview Stage */}
      <div className="flex-1 flex flex-col bg-slate-100 dark:bg-slate-950 min-w-0">
        {photos.length === 0 ? (
          <div className="flex-1 flex flex-col items-center justify-center p-8 text-center">
            <div className="w-16 h-16 rounded-2xl bg-pink-50 dark:bg-pink-500/15 text-pink-500 flex items-center justify-center mb-4">
              <ImageIcon className="w-8 h-8" />
            </div>
            <h3 className="text-base font-semibold text-slate-800 dark:text-slate-100">
              Select Photos to Print
            </h3>
            <p className="text-xs text-slate-500 dark:text-slate-400 max-w-md mt-1.5 mb-5">
              Preview 4×6, 5×7, or 8×10 prints with borderless edge-to-edge fill or clean margins,
              crop &amp; brightness adjustments.
            </p>
            <button
              onClick={handlePickPhotos}
              className="inline-flex items-center gap-2 px-5 py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-semibold"
            >
              <FolderOpen className="w-4 h-4" /> Choose Photos
            </button>
          </div>
        ) : (
          <>
            <div className="flex-1 min-h-0 p-8 flex flex-col items-center justify-center overflow-auto">
              {activePhoto && (
                <>
                  <div
                    style={{ aspectRatio: String(frameAspect) }}
                    className={`bg-white shadow-xl rounded overflow-hidden flex items-center justify-center transition-all ${
                      orientation === "landscape" ? "w-[560px] max-w-full" : "h-[520px] max-h-full"
                    } ${borderless ? "p-0" : "p-5"}`}
                  >
                    <img
                      src={toAssetUrl(activePhoto.edited_path, cacheBust)}
                      alt={activePhoto.source_label}
                      onLoad={() => {
                        if (rotatingId === activePhoto.id) {
                          setOptimisticDeg(0);
                          setRotatingId(null);
                        }
                      }}
                      style={
                        rotatingId === activePhoto.id && optimisticDeg !== 0
                          ? {
                              transform: `rotate(${optimisticDeg}deg)`,
                              transition: "transform 180ms cubic-bezier(0.22, 1, 0.36, 1)",
                            }
                          : {
                              transform: "rotate(0deg)",
                              transition: "none",
                            }
                      }
                      className={`w-full h-full ${
                        borderless ? "object-cover" : "object-contain"
                      }`}
                    />
                  </div>
                  <div className="mt-3 flex items-center gap-3 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-full px-4 py-1.5 text-xs shadow-sm">
                    <button
                      onClick={() => handleQuickRotate(activePhoto)}
                      disabled={rotatingId === activePhoto.id}
                      className="inline-flex items-center gap-1 hover:text-indigo-600 font-medium disabled:opacity-60"
                    >
                      <RotateCw className="w-3.5 h-3.5 transition-transform duration-200" />
                      Rotate
                    </button>
                    <button
                      onClick={() => setEditingPhoto(activePhoto)}
                      className="inline-flex items-center gap-1 text-indigo-600 dark:text-indigo-400 font-medium hover:underline"
                    >
                      <Edit3 className="w-3.5 h-3.5" /> Crop &amp; Tone
                    </button>
                  </div>
                </>
              )}
            </div>

            <div className="h-32 shrink-0 border-t border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 px-4 py-2.5 flex items-center gap-3 overflow-x-auto">
              {photos.map((ph) => (
                <div
                  key={ph.id}
                  onClick={() => setActiveId(ph.id)}
                  className={`relative h-full w-24 shrink-0 rounded-xl border-2 p-1 cursor-pointer bg-slate-50 dark:bg-slate-800 ${
                    activePhoto?.id === ph.id
                      ? "border-indigo-500"
                      : "border-slate-200 dark:border-slate-700"
                  }`}
                >
                  <img
                    src={toAssetUrl(ph.edited_path, cacheBust)}
                    alt={ph.source_label}
                    className="w-full h-full object-cover rounded"
                  />
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      setPhotos((prev) => prev.filter((x) => x.id !== ph.id));
                    }}
                    className="absolute -top-2 -right-2 p-1 rounded-full bg-red-600 text-white shadow"
                  >
                    <Trash2 className="w-3 h-3" />
                  </button>
                </div>
              ))}
            </div>
          </>
        )}
      </div>

      {editingPhoto && (
        <PageEditorModal
          page={editingPhoto}
          onClose={() => setEditingPhoto(null)}
          onSaved={(updated) => {
            setPhotos((prev) => prev.map((p) => (p.id === updated.id ? updated : p)));
            setCacheBust(Date.now());
          }}
        />
      )}
    </div>
  );
}
