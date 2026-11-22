import { useEffect, useState } from "react";
import {
  AlertTriangle,
  CheckCircle2,
  ExternalLink,
  RefreshCw,
  RotateCcw,
  Settings2,
  TestTube2,
  Trash2,
  Wrench,
  XCircle,
} from "lucide-react";
import { api } from "../lib/api";
import type { DiagnosticReport, PrinterInfo } from "../types";

interface DiagnosticsViewProps {
  printers: PrinterInfo[];
  selectedPrinterName: string;
  onSelectPrinter: (name: string) => void;
  onNotify: (msg: string, isError?: boolean) => void;
  onRefreshDevices: () => void;
}

export function DiagnosticsView({
  printers,
  selectedPrinterName,
  onSelectPrinter,
  onNotify,
  onRefreshDevices,
}: DiagnosticsViewProps) {
  const [report, setReport] = useState<DiagnosticReport | null>(null);
  const [running, setRunning] = useState(false);
  const [busyAction, setBusyAction] = useState<string | null>(null);

  const runCheck = async () => {
    setRunning(true);
    try {
      const res = await api.runDiagnostics(selectedPrinterName || null);
      setReport(res);
    } catch (e) {
      onNotify(String(e), true);
    } finally {
      setRunning(false);
    }
  };

  useEffect(() => {
    runCheck();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedPrinterName]);

  const handleClearQueue = async () => {
    if (!selectedPrinterName) return;
    setBusyAction("queue");
    try {
      const msg = await api.clearPrintQueue(selectedPrinterName);
      onNotify(msg);
      await runCheck();
    } catch (e) {
      onNotify(String(e), true);
    } finally {
      setBusyAction(null);
    }
  };

  const handleRestartSpooler = async () => {
    setBusyAction("spooler");
    try {
      const msg = await api.restartSpoolerElevated();
      onNotify(msg);
      onRefreshDevices();
      await runCheck();
    } catch (e) {
      onNotify(String(e), true);
    } finally {
      setBusyAction(null);
    }
  };

  const handleTestPage = async () => {
    if (!selectedPrinterName) return;
    setBusyAction("test");
    try {
      const msg = await api.printTestPage(selectedPrinterName);
      onNotify(msg);
    } catch (e) {
      onNotify(String(e), true);
    } finally {
      setBusyAction(null);
    }
  };

  return (
    <div className="p-6 max-w-5xl mx-auto space-y-6 overflow-y-auto h-full">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-xl font-bold text-slate-900 dark:text-white flex items-center gap-2">
            <Wrench className="w-5 h-5 text-sky-500" /> Diagnose &amp; Fix
          </h2>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
            Check Windows Print Spooler, WIA scanner connectivity, stuck queues, and driver health.
          </p>
        </div>

        <div className="flex items-center gap-2">
          <select
            value={selectedPrinterName}
            onChange={(e) => onSelectPrinter(e.target.value)}
            className="px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 text-xs font-medium"
          >
            {printers.map((p) => (
              <option key={p.name} value={p.name}>
                {p.name}
              </option>
            ))}
          </select>

          <button
            onClick={runCheck}
            disabled={running}
            className="inline-flex items-center gap-1.5 px-4 py-2 rounded-xl bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50 text-white text-xs font-semibold"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${running ? "animate-spin" : ""}`} />
            {running ? "Checking..." : "Run Diagnostics"}
          </button>
        </div>
      </div>

      {/* Quick Repair Toolbar */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <button
          onClick={handleClearQueue}
          disabled={!selectedPrinterName || busyAction !== null}
          className="flex items-center justify-center gap-2 p-3 rounded-xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 hover:border-indigo-400 text-xs font-semibold"
        >
          <Trash2 className="w-4 h-4 text-rose-500" />
          {busyAction === "queue" ? "Clearing..." : "Clear Print Queue"}
        </button>

        <button
          onClick={handleRestartSpooler}
          disabled={busyAction !== null}
          className="flex items-center justify-center gap-2 p-3 rounded-xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 hover:border-indigo-400 text-xs font-semibold"
        >
          <RotateCcw className="w-4 h-4 text-amber-500" />
          {busyAction === "spooler" ? "Restarting..." : "Restart Spooler & WIA"}
        </button>

        <button
          onClick={handleTestPage}
          disabled={!selectedPrinterName || busyAction !== null}
          className="flex items-center justify-center gap-2 p-3 rounded-xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 hover:border-indigo-400 text-xs font-semibold"
        >
          <TestTube2 className="w-4 h-4 text-indigo-500" />
          {busyAction === "test" ? "Printing..." : "Print Test Page"}
        </button>

        <button
          onClick={() =>
            selectedPrinterName && api.openPrinterDialog(selectedPrinterName, "preferences")
          }
          disabled={!selectedPrinterName}
          className="flex items-center justify-center gap-2 p-3 rounded-xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 hover:border-indigo-400 text-xs font-semibold"
        >
          <Settings2 className="w-4 h-4 text-emerald-500" />
          Driver Preferences
        </button>
      </div>

      {/* Diagnostic Check Results */}
      {report && (
        <div className="space-y-3">
          <div className="flex items-center justify-between text-xs text-slate-500">
            <span>Last checked: {report.checked_at}</span>
            {report.ip_address && (
              <button
                onClick={() => api.openPathOrUrl(`http://${report.ip_address}`)}
                className="inline-flex items-center gap-1 text-indigo-600 dark:text-indigo-400 hover:underline font-medium"
              >
                <ExternalLink className="w-3.5 h-3.5" /> Open Embedded Web Server (
                {report.ip_address})
              </button>
            )}
          </div>

          <div className="grid grid-cols-1 gap-3">
            {report.checks.map((chk) => {
              const Icon =
                chk.status === "pass"
                  ? CheckCircle2
                  : chk.status === "warn"
                  ? AlertTriangle
                  : XCircle;
              const badgeColor =
                chk.status === "pass"
                  ? "text-emerald-600 bg-emerald-50 dark:bg-emerald-500/15"
                  : chk.status === "warn"
                  ? "text-amber-600 bg-amber-50 dark:bg-amber-500/15"
                  : "text-rose-600 bg-rose-50 dark:bg-rose-500/15";

              return (
                <div
                  key={chk.id}
                  className="rounded-2xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 p-4 flex items-start justify-between gap-4"
                >
                  <div className="flex items-start gap-3">
                    <div className={`p-2 rounded-xl ${badgeColor}`}>
                      <Icon className="w-5 h-5" />
                    </div>
                    <div>
                      <h3 className="text-sm font-semibold text-slate-900 dark:text-white">
                        {chk.title}
                      </h3>
                      <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                        {chk.detail}
                      </p>
                      {chk.action_hint && (
                        <span className="inline-block mt-1.5 text-[11px] font-medium text-indigo-600 dark:text-indigo-400">
                          Suggested action: {chk.action_hint}
                        </span>
                      )}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>

          {report.queue_jobs.length > 0 && (
            <div className="rounded-2xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 p-4 space-y-2">
              <h3 className="text-sm font-semibold">Active Queue Jobs</h3>
              <div className="divide-y divide-slate-100 dark:divide-slate-800 text-xs">
                {report.queue_jobs.map((j) => (
                  <div key={j.id} className="py-2 flex items-center justify-between">
                    <div>
                      <span className="font-medium">#{j.id} — {j.document_name}</span>
                      <span className="text-slate-400 ml-2">({j.user_name})</span>
                    </div>
                    <span className="px-2 py-0.5 rounded bg-amber-50 dark:bg-amber-500/15 text-amber-700 dark:text-amber-300">
                      {j.status || "Queued"} · {j.total_pages}p
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
