import { useEffect, useState } from "react";
import {
  Activity,
  Droplets,
  ExternalLink,
  FileText,
  FolderOpen,
  Image as ImageIcon,
  Printer,
  RefreshCw,
  ScanLine,
  Settings2,
  TestTube2,
  Trash2,
  Wifi,
  Usb,
  Wrench,
  Zap,
} from "lucide-react";
import { api } from "../lib/api";
import type {
  ActivityEntry,
  PrinterInfo,
  PrinterNetworkStatus,
  ScannerInfo,
  TabId,
} from "../types";

interface DashboardViewProps {
  printers: PrinterInfo[];
  offlinePrinterCount: number;
  showOfflinePrinters: boolean;
  onToggleShowOfflinePrinters: () => void;
  scanners: ScannerInfo[];
  selectedPrinterName: string;
  onSelectPrinter: (name: string) => void;
  selectedScannerId: string;
  onSelectScanner: (id: string) => void;
  connectingDevice: string | null;
  recentActivity: ActivityEntry[];
  onClearActivity: () => void;
  onNavigate: (tab: TabId) => void;
  onNotify: (msg: string, isError?: boolean) => void;
  onImportShortcutTrigger: () => void;
}

export function DashboardView({
  printers,
  offlinePrinterCount,
  showOfflinePrinters,
  onToggleShowOfflinePrinters,
  scanners,
  selectedPrinterName,
  onSelectPrinter,
  selectedScannerId,
  onSelectScanner,
  connectingDevice,
  recentActivity,
  onClearActivity,
  onNavigate,
  onNotify,
  onImportShortcutTrigger,
}: DashboardViewProps) {
  const activePrinter = printers.find((p) => p.name === selectedPrinterName);
  const [netStatus, setNetStatus] = useState<PrinterNetworkStatus | null>(null);
  const [probingNet, setProbingNet] = useState(false);
  const [printingTest, setPrintingTest] = useState(false);

  useEffect(() => {
    setNetStatus(null);
    if (!activePrinter?.ip_address) return;
    let cancelled = false;
    setProbingNet(true);
    api
      .probeNetworkPrinterStatus(activePrinter.ip_address)
      .then((res) => {
        if (!cancelled) setNetStatus(res);
      })
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setProbingNet(false);
      });
    return () => {
      cancelled = true;
    };
  }, [activePrinter?.name, activePrinter?.ip_address]);

  const handleTestPage = async () => {
    if (!activePrinter) return;
    setPrintingTest(true);
    try {
      const msg = await api.printTestPage(activePrinter.name);
      onNotify(msg);
    } catch (e) {
      onNotify(String(e), true);
    } finally {
      setPrintingTest(false);
    }
  };

  const handleOpenPrefs = async () => {
    if (!activePrinter) return;
    try {
      await api.openPrinterDialog(activePrinter.name, "preferences");
    } catch (e) {
      onNotify(String(e), true);
    }
  };

  const quickTiles: {
    title: string;
    subtitle: string;
    icon: typeof ScanLine;
    accent: string;
    onClick: () => void;
  }[] = [
    {
      title: "Scan Documents & Photos",
      subtitle: "Flatbed, ADF, ID Card, Multi-Item split & offline OCR",
      icon: ScanLine,
      accent: "bg-indigo-500/10 text-indigo-600 dark:text-indigo-400",
      onClick: () => onNavigate("scan"),
    },
    {
      title: "Print Documents",
      subtitle: "PDF & image preview, N-up sheets, guided manual duplex",
      icon: FileText,
      accent: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
      onClick: () => onNavigate("print-docs"),
    },
    {
      title: "Print Photos",
      subtitle: "4×6, 5×7, 8×10 layouts with borderless fill or fit",
      icon: ImageIcon,
      accent: "bg-pink-500/10 text-pink-600 dark:text-pink-400",
      onClick: () => onNavigate("print-photos"),
    },
    {
      title: "One-Click Shortcuts",
      subtitle: "Automate scan → OCR → save → print workflows",
      icon: Zap,
      accent: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
      onClick: () => onNavigate("shortcuts"),
    },
    {
      title: "Diagnose & Fix",
      subtitle: "Fix offline status, clear stuck queues, restart Spooler",
      icon: Wrench,
      accent: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
      onClick: () => onNavigate("diagnostics"),
    },
    {
      title: "Import & Edit Files",
      subtitle: "Crop, auto-enhance, annotate, or OCR existing PDFs/images",
      icon: FolderOpen,
      accent: "bg-violet-500/10 text-violet-600 dark:text-violet-400",
      onClick: onImportShortcutTrigger,
    },
  ];

  return (
    <div className="p-6 max-w-6xl mx-auto space-y-6 overflow-y-auto h-full">
      {/* Device Hero Card */}
      <div className="rounded-2xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 p-6 shadow-sm">
        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-6">
          <div className="space-y-3 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold bg-indigo-50 dark:bg-indigo-500/15 text-indigo-700 dark:text-indigo-300">
                <Printer className="w-3.5 h-3.5" /> Active Devices
              </span>
              {activePrinter && (
                <span
                  className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-medium ${
                    activePrinter.is_offline
                      ? "bg-rose-50 dark:bg-rose-500/15 text-rose-700 dark:text-rose-300"
                      : activePrinter.status === "Normal"
                      ? "bg-emerald-50 dark:bg-emerald-500/15 text-emerald-700 dark:text-emerald-300"
                      : "bg-amber-50 dark:bg-amber-500/15 text-amber-700 dark:text-amber-300"
                  }`}
                >
                  {activePrinter.is_network ? (
                    <Wifi className="w-3 h-3" />
                  ) : (
                    <Usb className="w-3 h-3" />
                  )}
                  {activePrinter.status} · {activePrinter.is_network ? "Wi-Fi" : activePrinter.port_name}
                </span>
              )}
              {connectingDevice && (
                <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-sky-50 dark:bg-sky-500/15 text-sky-700 dark:text-sky-300">
                  <RefreshCw className="w-3 h-3 animate-spin" />
                  {connectingDevice}
                </span>
              )}
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              <div>
                <div className="flex items-center justify-between mb-1">
                  <label className="text-xs font-medium text-slate-500 dark:text-slate-400">
                    Default Printer
                  </label>
                  {offlinePrinterCount > 0 && (
                    <button
                      onClick={onToggleShowOfflinePrinters}
                      className="text-[11px] font-medium text-indigo-600 dark:text-indigo-400 hover:underline"
                    >
                      {showOfflinePrinters
                        ? "Hide offline"
                        : `Show offline (${offlinePrinterCount})`}
                    </button>
                  )}
                </div>
                <select
                  value={selectedPrinterName}
                  onChange={(e) => onSelectPrinter(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 text-sm font-medium"
                >
                  {printers.length === 0 && <option value="">No online printers detected</option>}
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
                <label className="text-xs font-medium text-slate-500 dark:text-slate-400 block mb-1">
                  Active Scanner
                </label>
                <select
                  value={selectedScannerId}
                  onChange={(e) => onSelectScanner(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 text-sm font-medium"
                >
                  {scanners.length === 0 && <option value="">No WIA / eSCL scanner found</option>}
                  {scanners.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            {activePrinter && (
              <div className="flex flex-wrap items-center gap-2 pt-1">
                <button
                  onClick={handleTestPage}
                  disabled={printingTest}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium border border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800 transition"
                >
                  <TestTube2 className="w-3.5 h-3.5 text-indigo-500" />
                  {printingTest ? "Sending..." : "Print Test Page"}
                </button>
                <button
                  onClick={handleOpenPrefs}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium border border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800 transition"
                >
                  <Settings2 className="w-3.5 h-3.5 text-slate-500" />
                  Driver Maintenance &amp; Preferences
                </button>
                {netStatus?.ews_reachable && (
                  <button
                    onClick={() => api.openPathOrUrl(netStatus.ews_url)}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium border border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800 transition"
                  >
                    <ExternalLink className="w-3.5 h-3.5 text-emerald-500" />
                    Open Web Dashboard ({netStatus.ip_address})
                  </button>
                )}
              </div>
            )}
          </div>

          {/* Ink / Supply Card */}
          <div className="lg:w-80 rounded-xl bg-slate-50 dark:bg-slate-800/70 border border-slate-200/80 dark:border-slate-700/70 p-4 space-y-2.5">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold flex items-center gap-1.5 text-slate-700 dark:text-slate-200">
                <Droplets className="w-4 h-4 text-indigo-500" /> Ink &amp; Supplies
              </span>
              {probingNet && (
                <span className="text-[11px] text-slate-400">Checking SNMP...</span>
              )}
            </div>

            {netStatus?.snmp_reachable && netStatus.supplies.length > 0 ? (
              <div className="space-y-2">
                {netStatus.supplies.map((sup, idx) => (
                  <div key={idx} className="space-y-1">
                    <div className="flex justify-between text-[11px]">
                      <span className="truncate font-medium text-slate-700 dark:text-slate-300">
                        {sup.name}
                      </span>
                      <span className="text-slate-500">
                        {sup.level_percent !== null ? `${sup.level_percent}%` : "Refillable Tank"}
                      </span>
                    </div>
                    <div className="h-2 rounded-full bg-slate-200 dark:bg-slate-700 overflow-hidden">
                      <div
                        className="h-full rounded-full transition-all"
                        style={{
                          width: `${sup.level_percent ?? 100}%`,
                          backgroundColor: sup.color,
                        }}
                      />
                    </div>
                  </div>
                ))}
                {netStatus.supplies.every((s) => s.level_percent === null) && (
                  <p className="text-[11px] text-slate-500 dark:text-slate-400 pt-1">
                    Continuous Ink Tank printer: liquid tank levels are checked visually through the
                    front tank windows.
                  </p>
                )}
              </div>
            ) : activePrinter?.ip_address ? (
              <p className="text-xs text-slate-500 dark:text-slate-400">
                Network printer at <span className="font-mono">{activePrinter.ip_address}</span>{" "}
                did not expose SNMP Printer-MIB supply levels. Check Driver Maintenance or EWS.
              </p>
            ) : (
              <div className="space-y-1.5">
                <div className="flex gap-1.5">
                  {[
                    { label: "BK", color: "bg-slate-800 dark:bg-slate-300" },
                    { label: "C", color: "bg-cyan-500" },
                    { label: "M", color: "bg-pink-500" },
                    { label: "Y", color: "bg-yellow-400" },
                  ].map((t) => (
                    <div
                      key={t.label}
                      className="flex-1 rounded-lg bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 p-2 text-center"
                    >
                      <div className={`w-3 h-3 rounded-full mx-auto mb-1 ${t.color}`} />
                      <div className="text-[10px] font-semibold text-slate-600 dark:text-slate-300">
                        {t.label}
                      </div>
                    </div>
                  ))}
                </div>
                <p className="text-[11px] text-slate-500 dark:text-slate-400">
                  Connected via USB ({activePrinter?.port_name ?? "local"}). Smart Tank ink levels
                  are checked visually on the front tanks or via Driver Maintenance.
                </p>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Quick Action Tiles */}
      <div>
        <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300 mb-3">
          Quick Actions
        </h2>
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {quickTiles.map((tile) => {
            const Icon = tile.icon;
            return (
              <button
                key={tile.title}
                onClick={tile.onClick}
                className="group text-left rounded-2xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 p-5 hover:border-indigo-400 dark:hover:border-indigo-500 hover:shadow-md transition-all"
              >
                <div
                  className={`w-11 h-11 rounded-xl flex items-center justify-center mb-3.5 ${tile.accent}`}
                >
                  <Icon className="w-5 h-5" />
                </div>
                <h3 className="font-semibold text-slate-900 dark:text-white group-hover:text-indigo-600 dark:group-hover:text-indigo-400 transition">
                  {tile.title}
                </h3>
                <p className="text-xs text-slate-500 dark:text-slate-400 mt-1 leading-relaxed">
                  {tile.subtitle}
                </p>
              </button>
            );
          })}
        </div>
      </div>

      {/* Recent Activity */}
      <div className="rounded-2xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 p-5">
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-sm font-semibold flex items-center gap-2 text-slate-800 dark:text-slate-200">
            <Activity className="w-4 h-4 text-indigo-500" /> Recent Local Activity
          </h2>
          <div className="flex items-center gap-3">
            <span className="text-[11px] text-slate-400">Stored locally in AppData</span>
            {recentActivity.length > 0 && (
              <button
                onClick={onClearActivity}
                className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg text-xs font-medium text-slate-500 hover:text-rose-600 hover:bg-rose-50 dark:hover:bg-rose-500/10 border border-slate-200 dark:border-slate-700 transition"
                title="Clear recent activity history"
              >
                <Trash2 className="w-3.5 h-3.5" /> Clear
              </button>
            )}
          </div>
        </div>

        {recentActivity.length === 0 ? (
          <p className="text-xs text-slate-500 dark:text-slate-400 py-4 text-center">
            No scans, exports, or print jobs recorded yet in this workspace.
          </p>
        ) : (
          <div className="divide-y divide-slate-100 dark:divide-slate-800">
            {recentActivity.slice(0, 6).map((act) => (
              <div
                key={act.id}
                className="py-2.5 flex items-center justify-between gap-3 text-xs"
              >
                <div className="min-w-0">
                  <div className="font-medium text-slate-800 dark:text-slate-200 truncate">
                    {act.title}
                  </div>
                  <div className="text-slate-500 dark:text-slate-400 truncate">
                    {act.detail} · {act.timestamp}
                  </div>
                </div>
                {act.output_path && (
                  <button
                    onClick={() => api.openPathOrUrl(act.output_path!)}
                    className="shrink-0 px-2.5 py-1 rounded-lg border border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800 text-slate-600 dark:text-slate-300"
                  >
                    Open
                  </button>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
