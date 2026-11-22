import {
  FileText,
  Home,
  Image as ImageIcon,
  Moon,
  Printer,
  RefreshCw,
  ScanLine,
  Sun,
  Wrench,
  Zap,
} from "lucide-react";
import type { PrinterInfo, ScannerInfo, TabId } from "../types";

interface SidebarProps {
  activeTab: TabId;
  onSelectTab: (tab: TabId) => void;
  printers: PrinterInfo[];
  scanners: ScannerInfo[];
  selectedPrinterName: string;
  selectedScannerId: string;
  loadingDevices: boolean;
  connectingDevice: string | null;
  onRefreshDevices: () => void;
  darkMode: boolean;
  onToggleDark: () => void;
}

const NAV_ITEMS: { id: TabId; label: string; icon: typeof Home }[] = [
  { id: "dashboard", label: "Dashboard", icon: Home },
  { id: "scan", label: "Scan & Edit", icon: ScanLine },
  { id: "print-docs", label: "Print Documents", icon: FileText },
  { id: "print-photos", label: "Print Photos", icon: ImageIcon },
  { id: "shortcuts", label: "Shortcuts", icon: Zap },
  { id: "diagnostics", label: "Diagnose & Fix", icon: Wrench },
];

export function Sidebar({
  activeTab,
  onSelectTab,
  printers,
  scanners,
  selectedPrinterName,
  selectedScannerId,
  loadingDevices,
  connectingDevice,
  onRefreshDevices,
  darkMode,
  onToggleDark,
}: SidebarProps) {
  const activePrinter = printers.find((p) => p.name === selectedPrinterName);
  const activeScanner = scanners.find((s) => s.id === selectedScannerId);

  return (
    <aside className="w-64 shrink-0 border-r border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 flex flex-col justify-between select-none">
      <div>
        <div className="px-5 py-5 flex items-center gap-3 border-b border-slate-100 dark:border-slate-800">
          <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-indigo-600 to-violet-600 text-white flex items-center justify-center shadow-sm">
            <Printer className="w-5 h-5" />
          </div>
          <div>
            <h1 className="text-base font-bold tracking-tight text-slate-900 dark:text-white leading-tight">
              AlPrint
            </h1>
            <p className="text-[11px] font-medium text-slate-500 dark:text-slate-400">
              by Almoukhtar Amiouni
            </p>
          </div>
        </div>

        <nav className="p-3 space-y-1">
          {NAV_ITEMS.map((item) => {
            const Icon = item.icon;
            const active = activeTab === item.id;
            return (
              <button
                key={item.id}
                onClick={() => onSelectTab(item.id)}
                className={`w-full flex items-center gap-3 px-3.5 py-2.5 rounded-xl text-sm font-medium transition-all ${
                  active
                    ? "bg-indigo-50 text-indigo-700 dark:bg-indigo-500/15 dark:text-indigo-300 shadow-sm"
                    : "text-slate-600 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800/70 hover:text-slate-900 dark:hover:text-slate-100"
                }`}
              >
                <Icon className="w-4 h-4 shrink-0" />
                <span>{item.label}</span>
              </button>
            );
          })}
        </nav>
      </div>

      <div className="p-3 border-t border-slate-100 dark:border-slate-800 space-y-2.5">
        <div className="rounded-xl bg-slate-50 dark:bg-slate-800/70 p-3 space-y-2 border border-slate-200/70 dark:border-slate-700/60">
          <div className="flex items-center justify-between">
            <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
              Active Hardware
            </span>
            <button
              onClick={onRefreshDevices}
              disabled={loadingDevices}
              title="Refresh printers & scanners"
              className="p-1 rounded-md text-slate-500 hover:text-indigo-600 hover:bg-white dark:hover:bg-slate-700 transition"
            >
              <RefreshCw
                className={`w-3.5 h-3.5 ${loadingDevices ? "animate-spin text-indigo-500" : ""}`}
              />
            </button>
          </div>

          <div className="space-y-1.5 text-xs">
            <div className="flex items-center gap-2">
              <span
                className={`w-2 h-2 rounded-full shrink-0 ${
                  activePrinter && !activePrinter.is_offline && activePrinter.status === "Normal"
                    ? "bg-emerald-500"
                    : activePrinter?.is_offline
                    ? "bg-rose-500"
                    : activePrinter
                    ? "bg-amber-500"
                    : "bg-slate-400"
                }`}
              />
              <span className="truncate font-medium text-slate-700 dark:text-slate-200">
                {activePrinter ? activePrinter.name : "No printer selected"}
              </span>
            </div>

            <div className="flex items-center gap-2">
              <span
                className={`w-2 h-2 rounded-full shrink-0 ${
                  activeScanner ? "bg-indigo-500" : "bg-slate-400"
                }`}
              />
              <span className="truncate text-slate-500 dark:text-slate-400">
                {activeScanner ? activeScanner.name : "No scanner detected"}
              </span>
            </div>

            {(loadingDevices || connectingDevice) && (
              <div className="pt-1 flex items-center gap-1.5 text-[11px] text-indigo-600 dark:text-indigo-400">
                <RefreshCw className="w-3 h-3 animate-spin shrink-0" />
                <span className="truncate">{connectingDevice ?? "Refreshing devices..."}</span>
              </div>
            )}
          </div>
        </div>

        <button
          onClick={onToggleDark}
          className="w-full flex items-center justify-between px-3 py-2 rounded-xl text-xs font-medium text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 transition"
        >
          <span className="flex items-center gap-2">
            {darkMode ? <Sun className="w-3.5 h-3.5" /> : <Moon className="w-3.5 h-3.5" />}
            {darkMode ? "Light Theme" : "Dark Theme"}
          </span>
          <span className="text-[10px] px-1.5 py-0.5 rounded bg-slate-200 dark:bg-slate-700">
            Offline
          </span>
        </button>
      </div>
    </aside>
  );
}
