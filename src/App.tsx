import { useCallback, useEffect, useMemo, useState } from "react";
import { AlertCircle, CheckCircle2, X } from "lucide-react";
import { Sidebar } from "./components/Sidebar";
import { api } from "./lib/api";
import { DashboardView } from "./views/DashboardView";
import { DiagnosticsView } from "./views/DiagnosticsView";
import { PrintDocumentsView } from "./views/PrintDocumentsView";
import { PrintPhotosView } from "./views/PrintPhotosView";
import { ScanView } from "./views/ScanView";
import { ShortcutsView } from "./views/ShortcutsView";
import type {
  ActivityEntry,
  PrinterInfo,
  ScanPage,
  ScannerInfo,
  ShortcutConfig,
  TabId,
} from "./types";

function isVirtualPrinter(p: PrinterInfo): boolean {
  const n = p.name.toLowerCase();
  return (
    n.includes("microsoft print to pdf") ||
    n.includes("onenote") ||
    n.includes("anydesk") ||
    n.includes("microsoft xps") ||
    p.port_name === "PORTPROMPT:" ||
    p.port_name === "nul:" ||
    p.port_name === "AD_Port"
  );
}

function printerPriority(p: PrinterInfo): number {
  const offline = Boolean(p.is_offline || p.status === "Offline");
  if (offline) return 3;
  if (isVirtualPrinter(p)) return 2;
  // Prefer dedicated/vendor driver (e.g. HP Smart Tank 580-590 PCL-3) over generic IPP class ghost
  if (p.driver_name === "Microsoft IPP Class Driver" && p.port_name.startsWith("WSD")) return 1;
  return 0;
}

export function App() {
  const [activeTab, setActiveTab] = useState<TabId>("dashboard");
  const [darkMode, setDarkMode] = useState<boolean>(() => {
    try {
      const saved =
        localStorage.getItem("alprint-theme") ?? localStorage.getItem("printdesk-theme");
      if (saved === "dark") return true;
      if (saved === "light") return false;
      return window.matchMedia("(prefers-color-scheme: dark)").matches;
    } catch {
      return false;
    }
  });

  const [printers, setPrinters] = useState<PrinterInfo[]>([]);
  const [showOfflinePrinters, setShowOfflinePrinters] = useState(false);
  const [scanners, setScanners] = useState<ScannerInfo[]>([]);
  const [selectedPrinterName, setSelectedPrinterName] = useState("");
  const [selectedScannerId, setSelectedScannerId] = useState("");
  const [loadingDevices, setLoadingDevices] = useState(false);
  const [connectingDevice, setConnectingDevice] = useState<string | null>(null);

  const [shortcuts, setShortcuts] = useState<ShortcutConfig[]>([]);
  const [recentActivity, setRecentActivity] = useState<ActivityEntry[]>([]);

  const sessionId = useMemo(() => `session-${Date.now()}`, []);
  const [scanPages, setScanPages] = useState<ScanPage[]>([]);
  const [incomingPrintPages, setIncomingPrintPages] = useState<ScanPage[]>([]);

  const [toast, setToast] = useState<{ msg: string; isError?: boolean } | null>(null);

  const notify = useCallback((msg: string, isError = false) => {
    setToast({ msg, isError });
  }, []);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 5000);
    return () => clearTimeout(t);
  }, [toast]);

  useEffect(() => {
    document.documentElement.classList.toggle("dark", darkMode);
    try {
      localStorage.setItem("alprint-theme", darkMode ? "dark" : "light");
    } catch {
      // Ignore storage failures
    }
  }, [darkMode]);

  const refreshDevices = useCallback(async () => {
    setLoadingDevices(true);
    setConnectingDevice("Scanning local devices...");
    try {
      const [rawPrinters, sList] = await Promise.all([api.listPrinters(), api.listScanners()]);
      const pList = [...rawPrinters].sort((a, b) => printerPriority(a) - printerPriority(b));
      setPrinters(pList);
      setScanners(sList);

      const onlinePrinters = pList.filter((p) => !p.is_offline && p.status !== "Offline");
      setSelectedPrinterName((prev) => {
        if (prev && onlinePrinters.some((p) => p.name === prev)) return prev;
        const hpOnline = onlinePrinters.find(
          (p) => p.name.toLowerCase().includes("smart tank") && printerPriority(p) === 0
        );
        const physicalOnline = onlinePrinters.find((p) => !isVirtualPrinter(p));
        const defOnline = onlinePrinters.find((p) => p.is_default);
        return (
          (hpOnline ?? physicalOnline ?? defOnline ?? onlinePrinters[0] ?? pList[0])?.name ?? ""
        );
      });

      setSelectedScannerId((prev) => {
        if (prev && sList.some((s) => s.id === prev)) return prev;
        return sList[0]?.id ?? "";
      });
    } catch (e) {
      notify(String(e), true);
    } finally {
      setLoadingDevices(false);
      setConnectingDevice(null);
    }
  }, [notify]);

  // Lazily fetch PrinterSettings capabilities ONLY for the selected online printer
  useEffect(() => {
    if (!selectedPrinterName) return;
    const target = printers.find((p) => p.name === selectedPrinterName);
    if (!target || target.capabilities_loaded || target.is_offline) return;

    let cancelled = false;
    setConnectingDevice(`Connecting to ${selectedPrinterName}...`);
    api
      .getPrinterCapabilities(selectedPrinterName)
      .then((updated) => {
        if (cancelled) return;
        setPrinters((prev) => prev.map((p) => (p.name === updated.name ? updated : p)));
      })
      .catch(() => {
        if (cancelled) return;
        setPrinters((prev) =>
          prev.map((p) =>
            p.name === selectedPrinterName ? { ...p, capabilities_loaded: true } : p
          )
        );
      })
      .finally(() => {
        if (!cancelled) setConnectingDevice(null);
      });

    return () => {
      cancelled = true;
    };
  }, [selectedPrinterName, printers]);

  // Lazily probe WIA scanner capabilities only when user visits the Scan tab
  useEffect(() => {
    if (activeTab !== "scan" || !selectedScannerId) return;
    const target = scanners.find((s) => s.id === selectedScannerId);
    if (!target || target.backend !== "wia" || target.capabilities_loaded) return;

    let cancelled = false;
    setConnectingDevice(`Connecting to ${target.name}...`);
    api
      .probeScannerCapabilities(selectedScannerId)
      .then((updated) => {
        if (cancelled) return;
        setScanners((prev) => prev.map((s) => (s.id === updated.id ? updated : s)));
      })
      .catch(() => {
        if (cancelled) return;
        setScanners((prev) =>
          prev.map((s) =>
            s.id === selectedScannerId ? { ...s, capabilities_loaded: true } : s
          )
        );
      })
      .finally(() => {
        if (!cancelled) setConnectingDevice(null);
      });

    return () => {
      cancelled = true;
    };
  }, [activeTab, selectedScannerId, scanners]);

  const refreshAppState = useCallback(async () => {
    try {
      const st = await api.getAppState();
      setShortcuts(st.shortcuts);
      setRecentActivity(st.recent_activity);
    } catch (e) {
      notify(String(e), true);
    }
  }, [notify]);

  const handleClearActivity = useCallback(async () => {
    try {
      await api.clearActivity();
      setRecentActivity([]);
      notify("Recent local activity cleared.");
    } catch (e) {
      notify(String(e), true);
    }
  }, [notify]);

  useEffect(() => {
    refreshDevices();
    refreshAppState();
  }, [refreshDevices, refreshAppState]);

  const handleSendToPrint = (pages: ScanPage[]) => {
    setIncomingPrintPages(pages);
    setActiveTab("print-docs");
  };

  const offlinePrinterCount = useMemo(
    () => printers.filter((p) => p.is_offline || p.status === "Offline").length,
    [printers]
  );

  const visiblePrinters = useMemo(
    () =>
      showOfflinePrinters
        ? printers
        : printers.filter((p) => !p.is_offline && p.status !== "Offline"),
    [printers, showOfflinePrinters]
  );

  return (
    <div className="h-screen w-screen flex bg-slate-50 dark:bg-slate-950 text-slate-900 dark:text-slate-100 overflow-hidden">
      <Sidebar
        activeTab={activeTab}
        onSelectTab={setActiveTab}
        printers={printers}
        scanners={scanners}
        selectedPrinterName={selectedPrinterName}
        selectedScannerId={selectedScannerId}
        loadingDevices={loadingDevices}
        connectingDevice={connectingDevice}
        onRefreshDevices={refreshDevices}
        darkMode={darkMode}
        onToggleDark={() => setDarkMode((d) => !d)}
      />

      <main className="flex-1 min-w-0 h-full overflow-hidden relative">
        {activeTab === "dashboard" && (
          <DashboardView
            printers={visiblePrinters}
            offlinePrinterCount={offlinePrinterCount}
            showOfflinePrinters={showOfflinePrinters}
            onToggleShowOfflinePrinters={() => setShowOfflinePrinters((v) => !v)}
            scanners={scanners}
            selectedPrinterName={selectedPrinterName}
            onSelectPrinter={setSelectedPrinterName}
            selectedScannerId={selectedScannerId}
            onSelectScanner={setSelectedScannerId}
            connectingDevice={connectingDevice}
            recentActivity={recentActivity}
            onClearActivity={handleClearActivity}
            onNavigate={setActiveTab}
            onNotify={notify}
            onImportShortcutTrigger={() => setActiveTab("scan")}
          />
        )}

        {activeTab === "scan" && (
          <ScanView
            sessionId={sessionId}
            scanners={scanners}
            selectedScannerId={selectedScannerId}
            onSelectScanner={setSelectedScannerId}
            onRefreshScanners={refreshDevices}
            pages={scanPages}
            onSetPages={setScanPages}
            onSendToPrint={handleSendToPrint}
            onNotify={notify}
            onActivityUpdated={refreshAppState}
          />
        )}

        {activeTab === "print-docs" && (
          <PrintDocumentsView
            sessionId={sessionId}
            printers={visiblePrinters}
            selectedPrinterName={selectedPrinterName}
            onSelectPrinter={setSelectedPrinterName}
            incomingPages={incomingPrintPages}
            onClearIncoming={() => setIncomingPrintPages([])}
            onNotify={notify}
            onActivityUpdated={refreshAppState}
          />
        )}

        {activeTab === "print-photos" && (
          <PrintPhotosView
            sessionId={sessionId}
            printers={visiblePrinters}
            selectedPrinterName={selectedPrinterName}
            onSelectPrinter={setSelectedPrinterName}
            onNotify={notify}
            onActivityUpdated={refreshAppState}
          />
        )}

        {activeTab === "shortcuts" && (
          <ShortcutsView
            sessionId={sessionId}
            shortcuts={shortcuts}
            onShortcutsChanged={setShortcuts}
            scanners={scanners}
            selectedScannerId={selectedScannerId}
            printers={visiblePrinters}
            selectedPrinterName={selectedPrinterName}
            onNotify={notify}
            onActivityUpdated={refreshAppState}
          />
        )}

        {activeTab === "diagnostics" && (
          <DiagnosticsView
            printers={printers}
            selectedPrinterName={selectedPrinterName}
            onSelectPrinter={setSelectedPrinterName}
            onNotify={notify}
            onRefreshDevices={refreshDevices}
          />
        )}

        {toast && (
          <div
            className={`fixed bottom-5 right-5 z-50 max-w-md rounded-2xl px-4 py-3 shadow-xl border flex items-center gap-3 text-xs font-medium ${
              toast.isError
                ? "bg-rose-50 dark:bg-rose-950 border-rose-200 dark:border-rose-800 text-rose-800 dark:text-rose-200"
                : "bg-white dark:bg-slate-900 border-slate-200 dark:border-slate-700 text-slate-800 dark:text-slate-100"
            }`}
          >
            {toast.isError ? (
              <AlertCircle className="w-4 h-4 text-rose-500 shrink-0" />
            ) : (
              <CheckCircle2 className="w-4 h-4 text-emerald-500 shrink-0" />
            )}
            <span className="break-words flex-1">{toast.msg}</span>
            <button onClick={() => setToast(null)} className="opacity-60 hover:opacity-100">
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
        )}
      </main>
    </div>
  );
}

export default App;
