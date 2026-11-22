package com.almoukhtar.alprint.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MobileTab(val label: String) {
    HOME("Home"),
    SCAN("Scan"),
    PRINT_DOCS("Print Docs"),
    PRINT_PHOTOS("Print Photos"),
    SHORTCUTS("Shortcuts")
}

enum class ScanInputSource(val label: String, val badge: String) {
    CAMERA("Phone Camera", "ML Kit Live Edge + Perspective"),
    WIFI_ESCL("Wi-Fi Flatbed", "Driverless eSCL / AirScan"),
    IMPORT("Import Files", "Gallery Photos or PDFs")
}

enum class ScanPreset(val label: String, val description: String) {
    DOCUMENT("Document", "Crisp text & paper documents with clean contrast enhancement."),
    PHOTO("Photo", "Full-color photo scan preserving natural tones and gradients."),
    ID_CARD("ID Card (2-Step)", "Guided Front + Back capture stitched onto a single A4/Letter page."),
    MULTI_ITEM("Multi-Item Split", "Detects multiple receipts or cards and splits them into separate pages."),
    BOOK("Book Spread", "Splits a two-page open book spread along the center spine into 2 pages.")
}

enum class DuplexMode(val label: String, val subtitle: String) {
    ONE_SIDED("One-Sided", "Print on front side only"),
    LONG_EDGE("Long Edge", "Flip on long edge (Book style)"),
    SHORT_EDGE("Short Edge", "Flip on short edge (Notepad style)"),
    MANUAL_TWO_PASS("Manual 2-Pass", "Odd sheets first, then guided flip for even sheets (auto blank back page)")
}

data class CropRect(
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 1f,
    val height: Float = 1f
) {
    fun clamped(): CropRect {
        val cx = x.coerceIn(0f, 0.92f)
        val cy = y.coerceIn(0f, 0.92f)
        val cw = width.coerceIn(0.08f, 1f - cx)
        val ch = height.coerceIn(0.08f, 1f - cy)
        return CropRect(cx, cy, cw, ch)
    }
}

data class AnnotationStroke(
    val id: String,
    val tool: String, // "pen", "highlighter", "text"
    val colorHex: Long,
    val size: Float,
    val points: List<Pair<Float, Float>>,
    val text: String? = null
)

data class PageEdits(
    val rotation: Int = 0,
    val crop: CropRect? = null,
    val suggestedCrop: CropRect? = null,
    val filter: String = "none", // "none", "auto_enhance", "grayscale", "bw_document"
    val brightness: Float = 0f,  // -100f..100f
    val contrast: Float = 0f,    // -100f..100f
    val saturation: Float = 0f,  // -100f..100f
    val annotations: List<AnnotationStroke> = emptyList()
)

data class ScanPage(
    val id: String,
    val title: String,
    val originalFilePath: String,
    val processedFilePath: String,
    val width: Int,
    val height: Int,
    val edits: PageEdits = PageEdits(),
    val sourceLabel: String = "Camera",
    val createdAt: Long = System.currentTimeMillis()
)

data class OcrWord(
    val text: String,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

data class OcrResult(
    val fullText: String,
    val words: List<OcrWord>,
    val suggestedTitle: String
)

data class DiscoveredDevice(
    val name: String,
    val ip: String,
    val port: Int = 80,
    val isScanner: Boolean = true,
    val isPrinter: Boolean = true
)

data class SupplyLevel(
    val name: String,
    val levelPercent: Int,
    val colorHex: Long
)

data class PrinterNetworkStatus(
    val ip: String,
    val reachable: Boolean,
    val modelName: String,
    val stateLabel: String,
    val esclAvailable: Boolean,
    val ewsUrl: String,
    val supplies: List<SupplyLevel>
)

data class EsclCapabilities(
    val makeAndModel: String,
    val maxDpi: Int = 600,
    val resolutions: List<Int> = listOf(150, 300, 600),
    val colorModes: List<String> = listOf("Color", "Grayscale", "Black & White"),
    val sources: List<String> = listOf("Flatbed")
)

data class ShortcutConfig(
    val id: String,
    val name: String,
    val subtitle: String,
    val source: ScanInputSource,
    val preset: ScanPreset,
    val dpi: Int = 300,
    val colorMode: String = "Color",
    val filter: String = "auto_enhance",
    val outputFormat: String = "PDF", // "PDF", "JPEG", "PRINT"
    val filenameTemplate: String = "Scan_{date}_{time}",
    val autoOcr: Boolean = false
)

data class ActivityEntry(
    val id: String,
    val type: String, // "scan", "print", "ocr", "shortcut"
    val title: String,
    val subtitle: String,
    val timestamp: Long = System.currentTimeMillis()
)

object FilenameFormatter {
    fun format(template: String, preset: String = "Scan", timestampMs: Long = System.currentTimeMillis()): String {
        val date = Date(timestampMs)
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
        val timeStr = SimpleDateFormat("HHmmss", Locale.US).format(date)
        val raw = template
            .replace("{date}", dateStr)
            .replace("{time}", timeStr)
            .replace("{preset}", preset.replace(Regex("[^A-Za-z0-9_-]"), "_"))
            .replace("{ts}", (timestampMs / 1000L).toString())
        val cleaned = raw.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim()
        return cleaned.ifEmpty { "AlPrint_${dateStr}_$timeStr" }
    }
}
