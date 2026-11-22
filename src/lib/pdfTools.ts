import { GlobalWorkerOptions, getDocument } from "pdfjs-dist";
import pdfWorkerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import { PDFDocument, StandardFonts, rgb } from "pdf-lib";
import { api } from "./api";
import type { OcrResult, ScanPage } from "../types";

GlobalWorkerOptions.workerSrc = pdfWorkerUrl;

export function base64ToUint8Array(b64: string): Uint8Array {
  const binary = atob(b64);
  const len = binary.length;
  const bytes = new Uint8Array(len);
  for (let i = 0; i < len; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

export function uint8ArrayToBase64(bytes: Uint8Array): string {
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

export interface RenderedPdfPage {
  pageNumber: number;
  dataUrl: string;
  width: number;
  height: number;
}

export async function rasterizePdfFile(
  filePath: string,
  scale = 2.0
): Promise<RenderedPdfPage[]> {
  const b64 = await api.readFileBase64(filePath);
  return rasterizePdfBytes(base64ToUint8Array(b64), scale);
}

export async function rasterizePdfBytes(
  data: Uint8Array,
  scale = 2.0
): Promise<RenderedPdfPage[]> {
  const loadingTask = getDocument({ data });
  const pdf = await loadingTask.promise;
  const pages: RenderedPdfPage[] = [];

  for (let i = 1; i <= pdf.numPages; i++) {
    const page = await pdf.getPage(i);
    const viewport = page.getViewport({ scale });
    const canvas = document.createElement("canvas");
    canvas.width = Math.ceil(viewport.width);
    canvas.height = Math.ceil(viewport.height);
    const ctx = canvas.getContext("2d");
    if (!ctx) continue;

    ctx.fillStyle = "#ffffff";
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    await page.render({ canvasContext: ctx, viewport }).promise;
    pages.push({
      pageNumber: i,
      dataUrl: canvas.toDataURL("image/png"),
      width: canvas.width,
      height: canvas.height,
    });
  }

  return pages;
}

/**
 * Generates a PDF (optionally Searchable PDF with invisible OCR text layer)
 * directly in memory using pdf-lib and returns base64 bytes.
 */
export async function buildPdfBase64(
  pages: ScanPage[],
  ocrByPageId?: Record<string, OcrResult>
): Promise<string> {
  const doc = await PDFDocument.create();
  doc.setCreator("AlPrint by Almoukhtar Amiouni");
  doc.setProducer("AlPrint Offline PDF Engine");

  const font = ocrByPageId ? await doc.embedFont(StandardFonts.Helvetica) : null;

  for (const p of pages) {
    const b64 = await api.readFileBase64(p.edited_path);
    const bytes = base64ToUint8Array(b64);
    const embedded = await doc.embedPng(bytes);

    // Convert pixel dimensions at page DPI to PDF points (72 pt/in)
    const dpi = p.dpi > 0 ? p.dpi : 200;
    const ptW = Math.max(72, (p.width / dpi) * 72);
    const ptH = Math.max(72, (p.height / dpi) * 72);
    const pdfPage = doc.addPage([ptW, ptH]);

    pdfPage.drawImage(embedded, {
      x: 0,
      y: 0,
      width: ptW,
      height: ptH,
    });

    const ocr = ocrByPageId?.[p.id];
    if (ocr && font) {
      for (const line of ocr.lines) {
        for (const word of line.words) {
          // pdf-lib StandardFonts Helvetica only supports WinAnsi characters
          const safeText = word.text.replace(/[^\x20-\x7E\xA0-\xFF]/g, "").trim();
          if (!safeText) continue;
          const boxH = Math.max(6, word.h * ptH);
          const x = word.x * ptW;
          const y = ptH - (word.y + word.h) * ptH;
          try {
            pdfPage.drawText(safeText, {
              x,
              y,
              size: boxH * 0.85,
              font,
              color: rgb(0, 0, 0),
              opacity: 0,
            });
          } catch {
            // Skip glyph if unencodable in WinAnsi
          }
        }
      }
    }
  }

  const outBytes = await doc.save();
  return uint8ArrayToBase64(outBytes);
}
