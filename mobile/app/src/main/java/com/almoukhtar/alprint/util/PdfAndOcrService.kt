package com.almoukhtar.alprint.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import com.almoukhtar.alprint.model.DuplexMode
import com.almoukhtar.alprint.model.OcrResult
import com.almoukhtar.alprint.model.OcrWord
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.roundToInt

object PdfAndOcrService {

    /**
     * Runs on-device ML Kit Text Recognition offline on a Bitmap, returning extracted text,
     * word bounding boxes (normalized 0..1), and a smart suggested filename.
     */
    suspend fun recognizeText(bitmap: Bitmap): OcrResult = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        val w = bitmap.width.toFloat().coerceAtLeast(1f)
        val h = bitmap.height.toFloat().coerceAtLeast(1f)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val words = mutableListOf<OcrWord>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        for (element in line.elements) {
                            val box = element.boundingBox
                            if (box != null && element.text.isNotBlank()) {
                                words.add(
                                    OcrWord(
                                        text = element.text,
                                        left = (box.left / w).coerceIn(0f, 1f),
                                        top = (box.top / h).coerceIn(0f, 1f),
                                        width = (box.width() / w).coerceIn(0.01f, 1f),
                                        height = (box.height() / h).coerceIn(0.01f, 1f)
                                    )
                                )
                            }
                        }
                    }
                }
                val firstLine = visionText.text
                    .lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.length >= 3 }
                    ?.replace(Regex("[^A-Za-z0-9 _-]"), "")
                    ?.trim()
                    ?.take(36)
                    ?.replace(Regex("\\s+"), "_")
                    ?.ifEmpty { null }
                    ?: "Scanned_Document"

                if (cont.isActive) {
                    cont.resume(
                        OcrResult(
                            fullText = visionText.text,
                            words = words,
                            suggestedTitle = firstLine
                        )
                    )
                }
            }
            .addOnFailureListener {
                if (cont.isActive) {
                    cont.resume(
                        OcrResult(
                            fullText = "",
                            words = emptyList(),
                            suggestedTitle = "Scanned_Document"
                        )
                    )
                }
            }
    }

    /**
     * Builds a multi-page PDF or Searchable PDF (with an invisible selectable text layer when OCR results exist).
     */
    suspend fun buildPdfFile(
        outputFile: File,
        pageBitmaps: List<Bitmap>,
        ocrByPageIndex: Map<Int, OcrResult> = emptyMap()
    ): File = withContext(Dispatchers.IO) {
        val pdf = PdfDocument()
        val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val invisibleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.TRANSPARENT
            alpha = 0
        }

        pageBitmaps.forEachIndexed { index, bmp ->
            val isLandscape = bmp.width > bmp.height
            val pageW = if (isLandscape) 842 else 595
            val pageH = if (isLandscape) 595 else 842
            val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, index + 1).create()
            val page = pdf.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)

            // Fit bitmap proportionally on the page
            val scale = min(pageW.toFloat() / bmp.width.toFloat(), pageH.toFloat() / bmp.height.toFloat())
            val drawW = (bmp.width * scale).roundToInt()
            val drawH = (bmp.height * scale).roundToInt()
            val left = (pageW - drawW) / 2
            val top = (pageH - drawH) / 2
            val destRect = Rect(left, top, left + drawW, top + drawH)
            canvas.drawBitmap(bmp, null, destRect, imagePaint)

            // Draw searchable OCR text layer aligned with word bounding boxes
            val ocr = ocrByPageIndex[index]
            if (ocr != null) {
                for (word in ocr.words) {
                    val wx = left + word.left * drawW
                    val wy = top + (word.top + word.height * 0.85f) * drawH
                    invisibleTextPaint.textSize = (word.height * drawH).coerceIn(6f, 36f)
                    canvas.drawText(word.text, wx, wy, invisibleTextPaint)
                }
            }

            pdf.finishPage(page)
        }

        FileOutputStream(outputFile).use { out ->
            pdf.writeTo(out)
        }
        pdf.close()
        outputFile
    }

    /**
     * Rasterizes a PDF or image URI into a list of preview Bitmaps.
     */
    suspend fun decodeUriToBitmaps(context: Context, uri: Uri, maxPages: Int = 40): List<Bitmap> =
        withContext(Dispatchers.IO) {
            val mime = context.contentResolver.getType(uri)?.lowercase() ?: ""
            val pathStr = uri.toString().lowercase()
            if (mime.contains("pdf") || pathStr.endsWith(".pdf")) {
                val tempPdf = File(context.cacheDir, "import_${System.currentTimeMillis()}.pdf")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempPdf).use { output -> input.copyTo(output) }
                }
                val pages = renderPdfFileToBitmaps(tempPdf, maxPages)
                tempPdf.delete()
                pages
            } else {
                val bmp = context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
                if (bmp != null) listOf(bmp) else emptyList()
            }
        }

    fun renderPdfFileToBitmaps(pdfFile: File, maxPages: Int = 40): List<Bitmap> {
        if (!pdfFile.exists()) return emptyList()
        val bitmaps = mutableListOf<Bitmap>()
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val count = min(renderer.pageCount, maxPages)
            for (i in 0 until count) {
                val page = renderer.openPage(i)
                val targetW = 1240
                val targetH = ((page.height.toFloat() / page.width.toFloat()) * targetW)
                    .roundToInt().coerceAtLeast(800)
                val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()
                bitmaps.add(bmp)
            }
        } catch (_: Exception) {
        } finally {
            try {
                renderer?.close()
            } catch (_: Exception) {
            }
            try {
                pfd?.close()
            } catch (_: Exception) {
            }
        }
        return bitmaps
    }

    /**
     * Returns the base pixel dimensions (width x height) for composing a sheet bitmap
     * at ~150 DPI for a selected paper size and orientation.
     */
    fun getPaperDimensions(paperSize: String, landscape: Boolean, dpi: Int = 150): Pair<Int, Int> {
        val (base150W, base150H) = when (paperSize) {
            "4x6\"" -> 1200 to 1800      // 4:6 (1:1.50)
            "5x7\"" -> 1250 to 1750      // 5:7 (1:1.40)
            "8x10\"" -> 1400 to 1750     // 8:10 (1:1.25)
            "Letter" -> 1275 to 1650     // 8.5:11 (1:1.294)
            "Legal" -> 1275 to 2100      // 8.5:14 (1:1.647)
            else -> 1240 to 1754         // A4 210:297 (1:1.414)
        }
        val clampedDpi = dpi.coerceIn(100, 300)
        val scale = clampedDpi / 150f
        val scaledW = if (clampedDpi == 150) base150W else (base150W * scale).roundToInt()
        val scaledH = if (clampedDpi == 150) base150H else (base150H * scale).roundToInt()
        return if (landscape) (scaledH to scaledW) else (scaledW to scaledH)
    }

    /**
     * Returns exact PDF PostScript points (72 pt/in) for Android PrintDocumentAdapter / PdfDocument.
     */
    fun getPaperPoints(paperSize: String, landscape: Boolean): Pair<Int, Int> {
        val (baseW, baseH) = when (paperSize) {
            "4x6\"" -> 288 to 432
            "5x7\"" -> 360 to 504
            "8x10\"" -> 576 to 720
            "Letter" -> 612 to 792
            "Legal" -> 612 to 1008
            else -> 595 to 842
        }
        return if (landscape) (baseH to baseW) else (baseW to baseH)
    }

    fun getPaperDimensionsLabel(paperSize: String, landscape: Boolean): String {
        return when (paperSize) {
            "4x6\"" -> if (landscape) "6 × 4 in (152 × 102 mm)" else "4 × 6 in (102 × 152 mm)"
            "5x7\"" -> if (landscape) "7 × 5 in (178 × 127 mm)" else "5 × 7 in (127 × 178 mm)"
            "8x10\"" -> if (landscape) "10 × 8 in (254 × 203 mm)" else "8 × 10 in (203 × 254 mm)"
            "Letter" -> if (landscape) "11 × 8.5 in (279 × 216 mm)" else "8.5 × 11 in (216 × 279 mm)"
            "Legal" -> if (landscape) "14 × 8.5 in (356 × 216 mm)" else "8.5 × 14 in (216 × 356 mm)"
            else -> if (landscape) "297 × 210 mm (A4)" else "210 × 297 mm (A4)"
        }
    }

    /**
     * Composes a single photo onto a physical paper sheet of the given paperSize ("4x6\"", "5x7\"", "8x10\"", "A4", "Letter"),
     * orientation (Portrait vs Landscape), and framing mode ("fill" = borderless edge-to-edge crop, "fit" = fit entire photo with white margins).
     */
    fun composePhotoSheet(
        photo: Bitmap,
        paperSize: String = "4x6\"",
        fillMode: String = "fill",
        landscape: Boolean = false,
        dpi: Int = 150
    ): Bitmap {
        val (sheetW, sheetH) = getPaperDimensions(paperSize, landscape, dpi)
        val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        if (fillMode == "fill") {
            // Borderless Fill: cover 100% of the paper sheet edge-to-edge
            val scale = maxOf(sheetW.toFloat() / photo.width.toFloat(), sheetH.toFloat() / photo.height.toFloat())
            val drawW = photo.width * scale
            val drawH = photo.height * scale
            val dx = (sheetW - drawW) / 2f
            val dy = (sheetH - drawH) / 2f
            canvas.drawBitmap(photo, null, RectF(dx, dy, dx + drawW, dy + drawH), paint)
        } else {
            // Fit Entire Photo: inset by a clean 5% white paper margin and fit the entire image without cropping
            val padX = sheetW * 0.05f
            val padY = sheetH * 0.05f
            val availW = sheetW - padX * 2f
            val availH = sheetH - padY * 2f
            val scale = minOf(availW / photo.width.toFloat(), availH / photo.height.toFloat())
            val drawW = photo.width * scale
            val drawH = photo.height * scale
            val dx = padX + (availW - drawW) / 2f
            val dy = padY + (availH - drawH) / 2f
            canvas.drawBitmap(photo, null, RectF(dx, dy, dx + drawW, dy + drawH), paint)
        }
        return sheet
    }

    /**
     * Composes source pages into N-up sheets (1, 2, or 4 pages per sheet) with optional grayscale
     * and scalePercent. Also supports Guided Manual 2-Pass Duplex (Pass 1 = odd sheets, Pass 2 = even sheets
     * with an automatic blank white back sheet when the total sheet count is odd!).
     */
    fun composePrintSheets(
        pages: List<Bitmap>,
        pagesPerSheet: Int = 1,
        grayscale: Boolean = false,
        scalePercent: Int = 100,
        fitMode: String = "fit", // "fit" or "fill"
        landscape: Boolean = false,
        paperSize: String = "A4",
        marginMode: String = "minimal", // "minimal", "normal", "none"
        dpi: Int = 150
    ): List<Bitmap> {
        if (pages.isEmpty()) return emptyList()
        val nUp = when (pagesPerSheet) {
            2 -> 2
            4 -> 4
            else -> 1
        }
        val (sheetW, sheetH) = getPaperDimensions(paperSize, landscape, dpi)
        val chunks = pages.chunked(nUp)
        val sheets = mutableListOf<Bitmap>()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (grayscale) {
            val cm = ColorMatrix().apply { setSaturation(0f) }
            paint.colorFilter = ColorMatrixColorFilter(cm)
        }

        val outerMarginFrac = when {
            fitMode == "fill" || marginMode == "none" -> 0f
            marginMode == "normal" -> 0.06f
            else -> 0.025f
        }
        val outerX = sheetW * outerMarginFrac
        val outerY = sheetH * outerMarginFrac
        val gutter = if (nUp > 1 && fitMode != "fill") min(sheetW, sheetH) * 0.02f else 0f

        for (chunk in chunks) {
            val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.WHITE)

            val cols = when (nUp) {
                2 -> if (landscape) 2 else 1
                4 -> 2
                else -> 1
            }
            val rows = when (nUp) {
                2 -> if (landscape) 1 else 2
                4 -> 2
                else -> 1
            }

            val availW = sheetW - outerX * 2f - gutter * (cols - 1)
            val availH = sheetH - outerY * 2f - gutter * (rows - 1)
            val cellW = (availW / cols).coerceAtLeast(1f)
            val cellH = (availH / rows).coerceAtLeast(1f)
            val userScale = (scalePercent.coerceIn(25, 100) / 100f)

            chunk.forEachIndexed { idx, bmp ->
                val col = idx % cols
                val row = idx / cols
                val cellLeft = outerX + col * (cellW + gutter)
                val cellTop = outerY + row * (cellH + gutter)

                val scale = if (fitMode == "fill") {
                    maxOf(cellW / bmp.width, cellH / bmp.height) * userScale
                } else {
                    minOf(cellW / bmp.width, cellH / bmp.height) * userScale
                }
                val drawW = bmp.width * scale
                val drawH = bmp.height * scale
                val dx = cellLeft + (cellW - drawW) / 2f
                val dy = cellTop + (cellH - drawH) / 2f

                canvas.save()
                canvas.clipRect(cellLeft, cellTop, cellLeft + cellW, cellTop + cellH)
                canvas.drawBitmap(bmp, null, RectF(dx, dy, dx + drawW, dy + drawH), paint)
                canvas.restore()
            }
            sheets.add(sheet)
        }
        return sheets
    }

    /**
     * Splits composed sheets into Pass 1 (odd sheets: 1, 3, 5...) and Pass 2 (even sheets: 2, 4, 6...).
     * When total sheets is odd (e.g. 1 or 3), automatically appends a blank white sheet to Pass 2
     * so the user never has to figure out which sheet stays unprinted when flipping the stack!
     */
    fun splitManualDuplexPasses(sheets: List<Bitmap>): Pair<List<Bitmap>, List<Bitmap>> {
        val pass1Odd = mutableListOf<Bitmap>()
        val pass2Even = mutableListOf<Bitmap>()
        sheets.forEachIndexed { idx, sheet ->
            if (idx % 2 == 0) {
                pass1Odd.add(sheet)
            } else {
                pass2Even.add(sheet)
            }
        }
        if (sheets.size % 2 == 1 && sheets.isNotEmpty()) {
            val sample = sheets.first()
            val blankBack = Bitmap.createBitmap(sample.width, sample.height, Bitmap.Config.ARGB_8888)
            Canvas(blankBack).drawColor(Color.WHITE)
            pass2Even.add(blankBack)
        }
        return Pair(pass1Odd, pass2Even)
    }

    /**
     * Parses a page range expression like "1-3, 5" (1-based) into 0-based indices.
     */
    fun parsePageRange(rangeExpr: String, totalPages: Int): List<Int> {
        val clean = rangeExpr.trim()
        if (clean.isEmpty()) return (0 until totalPages).toList()
        val indices = sortedSetOf<Int>()
        for (part in clean.split(",")) {
            val token = part.trim()
            if (token.isEmpty()) continue
            if (token.contains("-")) {
                val bounds = token.split("-")
                val start = bounds.getOrNull(0)?.trim()?.toIntOrNull() ?: 1
                val end = bounds.getOrNull(1)?.trim()?.toIntOrNull() ?: totalPages
                for (p in minOf(start, end)..maxOf(start, end)) {
                    if (p in 1..totalPages) indices.add(p - 1)
                }
            } else {
                val p = token.toIntOrNull()
                if (p != null && p in 1..totalPages) {
                    indices.add(p - 1)
                }
            }
        }
        return if (indices.isEmpty()) (0 until totalPages).toList() else indices.toList()
    }

    /**
     * Sends a list of composed sheet Bitmaps to Android's native PrintManager spooler (Mopria / IPP / Wi-Fi Direct).
     */
    fun printBitmapsWithAndroidSpooler(
        context: Context,
        jobName: String,
        sheets: List<Bitmap>,
        duplexMode: DuplexMode = DuplexMode.ONE_SIDED,
        grayscale: Boolean = false,
        landscape: Boolean = false,
        paperSize: String = "A4",
        dpi: Int = 300,
        onRenderForPageSize: ((targetPaperSize: String, isLandscape: Boolean, isMono: Boolean) -> List<Bitmap>)? = null
    ) {
        if (sheets.isEmpty()) return
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return

        val baseMediaSize = when (paperSize) {
            "4x6\"" -> PrintAttributes.MediaSize.NA_INDEX_4X6
            "5x7\"" -> PrintAttributes.MediaSize("NA_5X7", "5x7 in", 5000, 7000)
            "8x10\"" -> PrintAttributes.MediaSize("NA_8X10", "8x10 in", 8000, 10000)
            "Letter" -> PrintAttributes.MediaSize.NA_LETTER
            "Legal" -> PrintAttributes.MediaSize.NA_LEGAL
            else -> PrintAttributes.MediaSize.ISO_A4
        }

        val safeDpi = dpi.coerceIn(150, 1200)
        val attrsBuilder = PrintAttributes.Builder()
            .setColorMode(
                if (grayscale) PrintAttributes.COLOR_MODE_MONOCHROME
                else PrintAttributes.COLOR_MODE_COLOR
            )
            .setMediaSize(
                if (landscape) baseMediaSize.asLandscape()
                else baseMediaSize.asPortrait()
            )
            .setResolution(PrintAttributes.Resolution("alprint_${safeDpi}dpi", "$safeDpi DPI", safeDpi, safeDpi))

        when (duplexMode) {
            DuplexMode.LONG_EDGE -> attrsBuilder.setDuplexMode(PrintAttributes.DUPLEX_MODE_LONG_EDGE)
            DuplexMode.SHORT_EDGE -> attrsBuilder.setDuplexMode(PrintAttributes.DUPLEX_MODE_SHORT_EDGE)
            else -> attrsBuilder.setDuplexMode(PrintAttributes.DUPLEX_MODE_NONE)
        }

        val adapter = object : PrintDocumentAdapter() {
            private var activeMediaSize: PrintAttributes.MediaSize? = null
            private var activeMono: Boolean = grayscale
            private var currentSheets: List<Bitmap> = sheets

            private fun mapMediaToPaperSize(media: PrintAttributes.MediaSize?): Pair<String, Boolean> {
                if (media == null) return paperSize to landscape
                val isLand = !media.isPortrait
                val shortMils = min(media.widthMils, media.heightMils)
                val longMils = maxOf(media.widthMils, media.heightMils)
                val mapped = when {
                    shortMils in 3800..4200 && longMils in 5800..6200 -> "4x6\""
                    shortMils in 4800..5200 && longMils in 6800..7200 -> "5x7\""
                    shortMils in 7800..8200 && longMils in 9800..10200 -> "8x10\""
                    shortMils in 8300..8700 && longMils in 10800..11200 -> "Letter"
                    shortMils in 8300..8700 && longMils in 13700..14300 -> "Legal"
                    else -> "A4"
                }
                return mapped to isLand
            }

            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes?,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback?,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onLayoutCancelled()
                    return
                }
                activeMediaSize = newAttributes?.mediaSize
                activeMono = newAttributes?.colorMode == PrintAttributes.COLOR_MODE_MONOCHROME
                if (onRenderForPageSize != null && newAttributes != null) {
                    val (mappedSize, isLand) = mapMediaToPaperSize(activeMediaSize)
                    val rerendered = onRenderForPageSize(mappedSize, isLand, activeMono)
                    if (rerendered.isNotEmpty()) {
                        currentSheets = rerendered
                    }
                }
                val info = PrintDocumentInfo.Builder("$jobName.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(currentSheets.size)
                    .build()
                callback?.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor?,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                if (destination == null) {
                    callback?.onWriteFailed("No destination descriptor")
                    return
                }
                try {
                    val tempFile = File(context.cacheDir, "print_job_${System.currentTimeMillis()}.pdf")
                    val pdf = PdfDocument()
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

                    val media = activeMediaSize
                    val (pageW, pageH) = if (media != null && media.widthMils > 0 && media.heightMils > 0) {
                        ((media.widthMils * 72f) / 1000f).roundToInt().coerceAtLeast(144) to
                            ((media.heightMils * 72f) / 1000f).roundToInt().coerceAtLeast(144)
                    } else {
                        getPaperPoints(paperSize, landscape)
                    }

                    currentSheets.forEachIndexed { idx, bmp ->
                        val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, idx + 1).create()
                        val page = pdf.startPage(pageInfo)
                        page.canvas.drawColor(Color.WHITE)

                        val scale = min(
                            pageW.toFloat() / bmp.width.toFloat().coerceAtLeast(1f),
                            pageH.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
                        )
                        val drawW = bmp.width * scale
                        val drawH = bmp.height * scale
                        val dx = (pageW - drawW) / 2f
                        val dy = (pageH - drawH) / 2f
                        page.canvas.drawBitmap(bmp, null, RectF(dx, dy, dx + drawW, dy + drawH), paint)
                        pdf.finishPage(page)
                    }

                    FileOutputStream(tempFile).use { pdf.writeTo(it) }
                    pdf.close()

                    FileInputStream(tempFile).use { input ->
                        FileOutputStream(destination.fileDescriptor).use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempFile.delete()
                    callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback?.onWriteFailed(e.message)
                }
            }
        }

        printManager.print(jobName, adapter, attrsBuilder.build())
    }
}
