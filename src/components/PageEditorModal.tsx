import { useEffect, useMemo, useRef, useState } from "react";
import {
  Check,
  Crop,
  Eraser,
  Highlighter,
  PenTool,
  RotateCcw,
  RotateCw,
  Sliders,
  Type,
  X,
} from "lucide-react";
import { api, toAssetUrl } from "../lib/api";
import type { CropRect, PageEdits, ScanPage } from "../types";

export type EditorTab = "adjust" | "crop" | "annotate";

interface PageEditorModalProps {
  page: ScanPage;
  initialTab?: EditorTab;
  postScanCropMode?: boolean;
  onClose: () => void;
  onSaved: (updated: ScanPage) => void;
}

type DragHandle = "move" | "nw" | "ne" | "sw" | "se" | "n" | "s" | "e" | "w";

export function PageEditorModal({
  page,
  initialTab = "adjust",
  postScanCropMode = false,
  onClose,
  onSaved,
}: PageEditorModalProps) {
  const [tab, setTab] = useState<EditorTab>(initialTab);
  const [edits, setEdits] = useState<PageEdits>({ ...page.edits });
  const [visualRotateDeg, setVisualRotateDeg] = useState<number>(page.edits.rotate_deg);
  const [crop, setCrop] = useState<CropRect>(
    page.edits.crop ?? page.suggested_crop ?? { x: 0.04, y: 0.04, w: 0.92, h: 0.92 }
  );
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Stage sizing + natural image dimensions for auto-fit after rotation
  const stageRef = useRef<HTMLDivElement | null>(null);
  const [stageSize, setStageSize] = useState<{ w: number; h: number }>({ w: 720, h: 520 });
  const [imgSize, setImgSize] = useState<{ w: number; h: number }>({
    w: page.width || 1000,
    h: page.height || 1400,
  });

  // Annotation canvas state
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const bgImgRef = useRef<HTMLImageElement | null>(null);
  const [tool, setTool] = useState<"pen" | "highlighter" | "text">("pen");
  const [strokeColor, setStrokeColor] = useState("#dc2626");
  const [strokeWidth, setStrokeWidth] = useState(4);
  const [textStamp, setTextStamp] = useState("APPROVED");
  const [isDrawing, setIsDrawing] = useState(false);

  // Crop dragging state
  const cropBoxRef = useRef<HTMLDivElement | null>(null);
  const [dragMode, setDragMode] = useState<null | DragHandle>(null);
  const dragStartRef = useRef<{
    clientX: number;
    clientY: number;
    orig: CropRect;
  } | null>(null);

  const previewUrl = useMemo(
    () => toAssetUrl(page.original_path, page.width * 31 + page.height),
    [page.original_path, page.width, page.height]
  );
  const editedUrl = useMemo(
    () => toAssetUrl(page.edited_path, page.width * 31 + page.height),
    [page.edited_path, page.width, page.height]
  );

  // Track preview viewport size dynamically
  useEffect(() => {
    const el = stageRef.current;
    if (!el) return;
    const updateSize = () => {
      setStageSize({ w: el.clientWidth, h: el.clientHeight });
    };
    updateSize();
    const ro = new ResizeObserver(updateSize);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // Initialize annotation canvas when entering Annotate tab
  useEffect(() => {
    if (tab !== "annotate") return;
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.onload = () => {
      bgImgRef.current = img;
      const canvas = canvasRef.current;
      if (!canvas) return;
      canvas.width = img.naturalWidth;
      canvas.height = img.naturalHeight;
      const ctx = canvas.getContext("2d");
      if (!ctx) return;
      ctx.drawImage(img, 0, 0);
    };
    img.src = editedUrl;
  }, [tab, editedUrl]);

  // Crop mouse handling
  useEffect(() => {
    if (!dragMode) return;
    const onMove = (e: MouseEvent) => {
      const box = cropBoxRef.current;
      const start = dragStartRef.current;
      if (!box || !start) return;
      const rect = box.getBoundingClientRect();
      const dx = (e.clientX - start.clientX) / rect.width;
      const dy = (e.clientY - start.clientY) / rect.height;
      const o = start.orig;

      if (dragMode === "move") {
        const nx = Math.max(0, Math.min(1 - o.w, o.x + dx));
        const ny = Math.max(0, Math.min(1 - o.h, o.y + dy));
        setCrop({ ...o, x: nx, y: ny });
      } else {
        let nx = o.x;
        let ny = o.y;
        let nr = o.x + o.w;
        let nb = o.y + o.h;
        const minSize = 0.06;

        if (dragMode.includes("w")) {
          nx = Math.max(0, Math.min(nr - minSize, o.x + dx));
        }
        if (dragMode.includes("e")) {
          nr = Math.min(1, Math.max(nx + minSize, o.x + o.w + dx));
        }
        if (dragMode.includes("n")) {
          ny = Math.max(0, Math.min(nb - minSize, o.y + dy));
        }
        if (dragMode.includes("s")) {
          nb = Math.min(1, Math.max(ny + minSize, o.y + o.h + dy));
        }
        setCrop({ x: nx, y: ny, w: nr - nx, h: nb - ny });
      }
    };
    const onUp = () => {
      setDragMode(null);
      dragStartRef.current = null;
    };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
    return () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
  }, [dragMode]);

  const startDrag = (mode: DragHandle, e: React.MouseEvent) => {
    e.stopPropagation();
    e.preventDefault();
    setDragMode(mode);
    dragStartRef.current = { clientX: e.clientX, clientY: e.clientY, orig: { ...crop } };
  };

  const getCanvasPoint = (e: React.MouseEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current!;
    const rect = canvas.getBoundingClientRect();
    return {
      x: ((e.clientX - rect.left) / rect.width) * canvas.width,
      y: ((e.clientY - rect.top) / rect.height) * canvas.height,
    };
  };

  const handleCanvasMouseDown = (e: React.MouseEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    const pt = getCanvasPoint(e);

    if (tool === "text") {
      if (!textStamp.trim()) return;
      const fontSize = Math.max(24, Math.round(canvas.width * 0.03));
      ctx.save();
      ctx.font = `bold ${fontSize}px Segoe UI, sans-serif`;
      ctx.fillStyle = strokeColor;
      ctx.fillText(textStamp, pt.x, pt.y);
      ctx.restore();
      return;
    }

    setIsDrawing(true);
    ctx.save();
    ctx.beginPath();
    ctx.moveTo(pt.x, pt.y);
    ctx.lineCap = "round";
    ctx.lineJoin = "round";
    const scaleFactor = Math.max(1, canvas.width / 800);
    if (tool === "highlighter") {
      ctx.globalAlpha = 0.35;
      ctx.strokeStyle = "#facc15";
      ctx.lineWidth = strokeWidth * 5 * scaleFactor;
    } else {
      ctx.globalAlpha = 1;
      ctx.strokeStyle = strokeColor;
      ctx.lineWidth = strokeWidth * scaleFactor;
    }
  };

  const handleCanvasMouseMove = (e: React.MouseEvent<HTMLCanvasElement>) => {
    if (!isDrawing) return;
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext("2d");
    if (!ctx || !canvas) return;
    const pt = getCanvasPoint(e);
    ctx.lineTo(pt.x, pt.y);
    ctx.stroke();
  };

  const handleCanvasMouseUp = () => {
    if (!isDrawing) return;
    setIsDrawing(false);
    const ctx = canvasRef.current?.getContext("2d");
    ctx?.closePath();
    ctx?.restore();
  };

  const handleClearMarkup = () => {
    const canvas = canvasRef.current;
    const img = bgImgRef.current;
    if (!canvas || !img) return;
    const ctx = canvas.getContext("2d");
    ctx?.clearRect(0, 0, canvas.width, canvas.height);
    ctx?.drawImage(img, 0, 0);
  };

  const handleCommit = async () => {
    setSaving(true);
    setError(null);
    try {
      if (tab === "annotate" && canvasRef.current) {
        const dataUrl = canvasRef.current.toDataURL("image/png");
        const updated = await api.overwritePageImage(page, dataUrl);
        onSaved(updated);
        onClose();
        return;
      }

      const finalEdits: PageEdits = {
        ...edits,
        crop: tab === "crop" ? crop : edits.crop,
      };
      const updated = await api.updatePageEdits(page, finalEdits);
      onSaved(updated);
      onClose();
    } catch (err) {
      setError(String(err));
    } finally {
      setSaving(false);
    }
  };

  const cssFilter = [
    `brightness(${100 + edits.brightness * 0.75}%)`,
    `contrast(${100 + edits.contrast * 0.8}%)`,
    `saturate(${100 + edits.saturation}%)`,
    edits.filter === "grayscale" ? "grayscale(100%)" : "",
    edits.filter === "bw_document" ? "grayscale(100%) contrast(210%)" : "",
    edits.filter === "auto_enhance" ? "contrast(118%) brightness(106%)" : "",
  ]
    .filter(Boolean)
    .join(" ");

  // Auto-fit rotated image cleanly inside stageRef viewport
  const isOddRot = Math.abs(edits.rotate_deg % 180) === 90;
  const visW = isOddRot ? imgSize.h : imgSize.w;
  const visH = isOddRot ? imgSize.w : imgSize.h;
  const fitScale = Math.max(
    0.05,
    Math.min(
      Math.max(120, stageSize.w - 48) / Math.max(1, visW),
      Math.max(120, stageSize.h - 48) / Math.max(1, visH)
    )
  );
  const fittedBoxW = Math.round(visW * fitScale);
  const fittedBoxH = Math.round(visH * fitScale);
  const fittedImgW = Math.round(imgSize.w * fitScale);
  const fittedImgH = Math.round(imgSize.h * fitScale);

  const CROP_HANDLES: { id: DragHandle; pos: string }[] = [
    { id: "nw", pos: "-top-2 -left-2 cursor-nwse-resize" },
    { id: "ne", pos: "-top-2 -right-2 cursor-nesw-resize" },
    { id: "sw", pos: "-bottom-2 -left-2 cursor-nesw-resize" },
    { id: "se", pos: "-bottom-2 -right-2 cursor-nwse-resize" },
    { id: "n", pos: "-top-2 left-1/2 -translate-x-1/2 cursor-ns-resize" },
    { id: "s", pos: "-bottom-2 left-1/2 -translate-x-1/2 cursor-ns-resize" },
    { id: "w", pos: "top-1/2 -left-2 -translate-y-1/2 cursor-ew-resize" },
    { id: "e", pos: "top-1/2 -right-2 -translate-y-1/2 cursor-ew-resize" },
  ];

  return (
    <div className="fixed inset-0 z-50 bg-slate-950/70 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="w-full max-w-5xl h-[88vh] bg-white dark:bg-slate-900 rounded-2xl border border-slate-200 dark:border-slate-700 shadow-2xl flex flex-col overflow-hidden">
        <div className="px-5 py-3.5 border-b border-slate-200 dark:border-slate-800 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <h2 className="font-semibold text-slate-900 dark:text-white">
              {postScanCropMode && tab === "crop"
                ? "Adjust Detected Edges"
                : `Edit Page — ${page.source_label}`}
            </h2>
            <div className="ml-4 inline-flex rounded-lg bg-slate-100 dark:bg-slate-800 p-1">
              {(
                [
                  { id: "adjust", label: "Adjust & Filter", icon: Sliders },
                  { id: "crop", label: "Crop", icon: Crop },
                  { id: "annotate", label: "Markup & Text", icon: PenTool },
                ] as const
              ).map((t) => {
                const Icon = t.icon;
                return (
                  <button
                    key={t.id}
                    onClick={() => setTab(t.id)}
                    className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition ${
                      tab === t.id
                        ? "bg-white dark:bg-slate-700 text-indigo-600 dark:text-indigo-300 shadow-sm"
                        : "text-slate-600 dark:text-slate-400 hover:text-slate-900"
                    }`}
                  >
                    <Icon className="w-3.5 h-3.5" />
                    {t.label}
                  </button>
                );
              })}
            </div>
          </div>

          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-800"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="flex-1 min-h-0 flex">
          {/* Preview Area */}
          <div
            ref={stageRef}
            className="flex-1 bg-slate-100 dark:bg-slate-950 p-6 flex items-center justify-center overflow-hidden"
          >
            {tab === "adjust" && (
              <div
                style={{ width: `${fittedBoxW}px`, height: `${fittedBoxH}px` }}
                className="relative flex items-center justify-center shrink-0"
              >
                <img
                  src={previewUrl}
                  alt="Preview"
                  onLoad={(e) => {
                    const el = e.currentTarget;
                    if (el.naturalWidth > 0 && el.naturalHeight > 0) {
                      setImgSize({ w: el.naturalWidth, h: el.naturalHeight });
                    }
                  }}
                  style={{
                    width: `${fittedImgW}px`,
                    height: `${fittedImgH}px`,
                    maxWidth: "none",
                    maxHeight: "none",
                    filter: cssFilter,
                    transform: `rotate(${visualRotateDeg}deg)`,
                  }}
                  className="rounded shadow-md transition-all duration-200 ease-out shrink-0"
                />
              </div>
            )}

            {tab === "crop" && (
              <div
                ref={cropBoxRef}
                style={{ width: `${fittedBoxW}px`, height: `${fittedBoxH}px` }}
                className="relative flex items-center justify-center select-none overflow-hidden shrink-0 rounded"
              >
                <img
                  src={previewUrl}
                  alt="Crop target"
                  onLoad={(e) => {
                    const el = e.currentTarget;
                    if (el.naturalWidth > 0 && el.naturalHeight > 0) {
                      setImgSize({ w: el.naturalWidth, h: el.naturalHeight });
                    }
                  }}
                  style={{
                    width: `${fittedImgW}px`,
                    height: `${fittedImgH}px`,
                    maxWidth: "none",
                    maxHeight: "none",
                    filter: cssFilter,
                    transform: `rotate(${edits.rotate_deg}deg)`,
                  }}
                  className="block rounded shrink-0 pointer-events-none"
                  draggable={false}
                />
                {/* Dimmed overlay outside crop */}
                <div
                  style={{
                    left: `${crop.x * 100}%`,
                    top: `${crop.y * 100}%`,
                    width: `${crop.w * 100}%`,
                    height: `${crop.h * 100}%`,
                  }}
                  onMouseDown={(e) => startDrag("move", e)}
                  className="absolute border-2 border-indigo-500 shadow-[0_0_0_9999px_rgba(15,23,42,0.55)] cursor-move"
                >
                  {CROP_HANDLES.map((h) => (
                    <div
                      key={h.id}
                      onMouseDown={(e) => startDrag(h.id, e)}
                      className={`absolute w-3.5 h-3.5 bg-white border-2 border-indigo-600 rounded-full ${h.pos}`}
                    />
                  ))}
                </div>
              </div>
            )}

            {tab === "annotate" && (
              <canvas
                ref={canvasRef}
                onMouseDown={handleCanvasMouseDown}
                onMouseMove={handleCanvasMouseMove}
                onMouseUp={handleCanvasMouseUp}
                onMouseLeave={handleCanvasMouseUp}
                style={{
                  maxWidth: `${Math.max(200, stageSize.w - 48)}px`,
                  maxHeight: `${Math.max(200, stageSize.h - 48)}px`,
                }}
                className="object-contain rounded shadow-md cursor-crosshair bg-white"
              />
            )}
          </div>

          {/* Right Inspector Controls */}
          <div className="w-72 border-l border-slate-200 dark:border-slate-800 p-4 flex flex-col justify-between overflow-y-auto">
            <div className="space-y-5">
              {tab === "adjust" && (
                <>
                  <div>
                    <label className="text-xs font-semibold uppercase tracking-wider text-slate-500 block mb-2">
                      Rotate
                    </label>
                    <div className="grid grid-cols-2 gap-2">
                      <button
                        onClick={() => {
                          setVisualRotateDeg((d) => d - 90);
                          setCrop((c) => ({
                            x: Math.max(0, Math.min(1, c.y)),
                            y: Math.max(0, Math.min(1, 1 - (c.x + c.w))),
                            w: c.h,
                            h: c.w,
                          }));
                          setEdits((e) => ({
                            ...e,
                            rotate_deg: (e.rotate_deg + 270) % 360,
                          }));
                        }}
                        className="flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg border border-slate-200 dark:border-slate-700 text-xs font-medium hover:bg-slate-50 dark:hover:bg-slate-800"
                      >
                        <RotateCcw className="w-3.5 h-3.5" /> -90°
                      </button>
                      <button
                        onClick={() => {
                          setVisualRotateDeg((d) => d + 90);
                          setCrop((c) => ({
                            x: Math.max(0, Math.min(1, 1 - (c.y + c.h))),
                            y: Math.max(0, Math.min(1, c.x)),
                            w: c.h,
                            h: c.w,
                          }));
                          setEdits((e) => ({
                            ...e,
                            rotate_deg: (e.rotate_deg + 90) % 360,
                          }));
                        }}
                        className="flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg border border-slate-200 dark:border-slate-700 text-xs font-medium hover:bg-slate-50 dark:hover:bg-slate-800"
                      >
                        <RotateCw className="w-3.5 h-3.5" /> +90°
                      </button>
                    </div>
                  </div>

                  <div>
                    <label className="text-xs font-semibold uppercase tracking-wider text-slate-500 block mb-2">
                      Enhancement Filter
                    </label>
                    <div className="grid grid-cols-2 gap-1.5">
                      {(
                        [
                          { id: "none", label: "Original" },
                          { id: "auto_enhance", label: "Auto-Enhance" },
                          { id: "grayscale", label: "Grayscale" },
                          { id: "bw_document", label: "B&W Crisp" },
                        ] as const
                      ).map((f) => (
                        <button
                          key={f.id}
                          onClick={() => setEdits((e) => ({ ...e, filter: f.id }))}
                          className={`px-2.5 py-2 rounded-lg text-xs font-medium border transition ${
                            edits.filter === f.id
                              ? "border-indigo-500 bg-indigo-50 dark:bg-indigo-500/20 text-indigo-700 dark:text-indigo-300"
                              : "border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800"
                          }`}
                        >
                          {f.label}
                        </button>
                      ))}
                    </div>
                  </div>

                  {(
                    [
                      { key: "brightness", label: "Brightness" },
                      { key: "contrast", label: "Contrast" },
                      { key: "saturation", label: "Saturation" },
                    ] as const
                  ).map((slider) => (
                    <div key={slider.key}>
                      <div className="flex justify-between text-xs mb-1">
                        <span className="font-medium text-slate-700 dark:text-slate-300">
                          {slider.label}
                        </span>
                        <span className="text-slate-500">{edits[slider.key]}</span>
                      </div>
                      <input
                        type="range"
                        min={-80}
                        max={80}
                        value={edits[slider.key]}
                        onChange={(e) =>
                          setEdits((prev) => ({
                            ...prev,
                            [slider.key]: Number(e.target.value),
                          }))
                        }
                        className="w-full accent-indigo-600"
                      />
                    </div>
                  ))}

                  <button
                    onClick={() =>
                      setEdits({
                        crop: null,
                        rotate_deg: 0,
                        brightness: 0,
                        contrast: 0,
                        saturation: 0,
                        filter: "none",
                      })
                    }
                    className="w-full py-2 text-xs font-medium text-slate-500 hover:text-slate-800 dark:hover:text-slate-200 border border-dashed border-slate-300 dark:border-slate-700 rounded-lg"
                  >
                    Reset All Adjustments
                  </button>
                </>
              )}

              {tab === "crop" && (
                <div className="space-y-4">
                  {postScanCropMode && (
                    <div className="rounded-xl bg-indigo-50 dark:bg-indigo-500/15 border border-indigo-200 dark:border-indigo-800 p-3 text-xs text-indigo-800 dark:text-indigo-200">
                      {page.suggested_crop
                        ? "Edges detected automatically. Drag any corner or edge handle to adjust before applying."
                        : "Adjust the crop handles to match your document edges, or keep the full scan."}
                    </div>
                  )}
                  <p className="text-xs text-slate-500 dark:text-slate-400">
                    Drag any of the 8 edge/corner handles or drag inside the box to reposition.
                  </p>
                  <div className="text-xs bg-slate-50 dark:bg-slate-800 rounded-lg p-3 space-y-1 font-mono">
                    <div>Left: {Math.round(crop.x * 100)}%</div>
                    <div>Top: {Math.round(crop.y * 100)}%</div>
                    <div>Width: {Math.round(crop.w * 100)}%</div>
                    <div>Height: {Math.round(crop.h * 100)}%</div>
                  </div>
                  <button
                    onClick={() => setCrop({ x: 0, y: 0, w: 1, h: 1 })}
                    className="w-full py-2 text-xs font-medium rounded-lg border border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800"
                  >
                    Select Full Page
                  </button>
                </div>
              )}

              {tab === "annotate" && (
                <div className="space-y-4">
                  <div>
                    <label className="text-xs font-semibold uppercase tracking-wider text-slate-500 block mb-2">
                      Tool
                    </label>
                    <div className="grid grid-cols-3 gap-1.5">
                      <button
                        onClick={() => setTool("pen")}
                        className={`flex flex-col items-center gap-1 p-2 rounded-lg border text-xs ${
                          tool === "pen"
                            ? "border-indigo-500 bg-indigo-50 dark:bg-indigo-500/20 text-indigo-700 dark:text-indigo-300"
                            : "border-slate-200 dark:border-slate-700"
                        }`}
                      >
                        <PenTool className="w-4 h-4" /> Pen
                      </button>
                      <button
                        onClick={() => setTool("highlighter")}
                        className={`flex flex-col items-center gap-1 p-2 rounded-lg border text-xs ${
                          tool === "highlighter"
                            ? "border-indigo-500 bg-indigo-50 dark:bg-indigo-500/20 text-indigo-700 dark:text-indigo-300"
                            : "border-slate-200 dark:border-slate-700"
                        }`}
                      >
                        <Highlighter className="w-4 h-4" /> Highlight
                      </button>
                      <button
                        onClick={() => setTool("text")}
                        className={`flex flex-col items-center gap-1 p-2 rounded-lg border text-xs ${
                          tool === "text"
                            ? "border-indigo-500 bg-indigo-50 dark:bg-indigo-500/20 text-indigo-700 dark:text-indigo-300"
                            : "border-slate-200 dark:border-slate-700"
                        }`}
                      >
                        <Type className="w-4 h-4" /> Text
                      </button>
                    </div>
                  </div>

                  {tool !== "highlighter" && (
                    <div>
                      <label className="text-xs font-semibold text-slate-500 block mb-1.5">
                        Ink Color
                      </label>
                      <div className="flex gap-2">
                        {["#dc2626", "#2563eb", "#0f172a", "#16a34a"].map((c) => (
                          <button
                            key={c}
                            onClick={() => setStrokeColor(c)}
                            style={{ backgroundColor: c }}
                            className={`w-7 h-7 rounded-full border-2 ${
                              strokeColor === c
                                ? "border-indigo-500 scale-110"
                                : "border-white dark:border-slate-700"
                            }`}
                          />
                        ))}
                      </div>
                    </div>
                  )}

                  {tool === "text" ? (
                    <div>
                      <label className="text-xs font-semibold text-slate-500 block mb-1">
                        Text to Stamp (click image)
                      </label>
                      <input
                        type="text"
                        value={textStamp}
                        onChange={(e) => setTextStamp(e.target.value)}
                        className="w-full px-3 py-1.5 text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800"
                      />
                    </div>
                  ) : (
                    <div>
                      <div className="flex justify-between text-xs mb-1">
                        <span>Stroke Size</span>
                        <span>{strokeWidth}px</span>
                      </div>
                      <input
                        type="range"
                        min={1}
                        max={12}
                        value={strokeWidth}
                        onChange={(e) => setStrokeWidth(Number(e.target.value))}
                        className="w-full accent-indigo-600"
                      />
                    </div>
                  )}

                  <button
                    onClick={handleClearMarkup}
                    className="w-full flex items-center justify-center gap-1.5 py-2 text-xs font-medium rounded-lg border border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800"
                  >
                    <Eraser className="w-3.5 h-3.5" /> Clear Unsaved Markup
                  </button>
                </div>
              )}
            </div>

            <div className="space-y-2 pt-4 border-t border-slate-200 dark:border-slate-800">
              {error && (
                <p className="text-xs text-red-600 dark:text-red-400 break-words">{error}</p>
              )}
              {postScanCropMode && tab === "crop" && (
                <button
                  onClick={onClose}
                  className="w-full py-2 px-4 rounded-xl border border-slate-200 dark:border-slate-700 text-xs font-medium hover:bg-slate-50 dark:hover:bg-slate-800"
                >
                  Keep Full Page (Skip Crop)
                </button>
              )}
              <button
                onClick={handleCommit}
                disabled={saving}
                className="w-full flex items-center justify-center gap-2 py-2.5 px-4 rounded-xl bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50 text-white text-sm font-semibold shadow-sm"
              >
                <Check className="w-4 h-4" />
                {saving
                  ? "Applying..."
                  : tab === "crop"
                  ? "Apply Crop"
                  : "Apply Changes"}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
