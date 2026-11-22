package com.almoukhtar.alprint.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.almoukhtar.alprint.model.AnnotationStroke
import com.almoukhtar.alprint.model.CropRect
import com.almoukhtar.alprint.model.PageEdits
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageProcessor {

    /**
     * Pure-Kotlin Otsu threshold calculator over a 256-bin luminance histogram.
     */
    fun otsuThreshold(hist: IntArray, total: Int): Int {
        if (total <= 0 || hist.size < 256) return 128
        var sum = 0.0
        for (i in 0 until 256) {
            sum += i * hist[i].toDouble()
        }
        var sumB = 0.0
        var wB = 0
        var maxVar = -1.0
        var threshold = 128

        for (t in 0 until 256) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break

            sumB += t * hist[t].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val diff = mB - mF
            val betweenVar = wB.toDouble() * wF.toDouble() * diff * diff
            if (betweenVar > maxVar) {
                maxVar = betweenVar
                threshold = t
            }
        }
        return threshold.coerceIn(45, 220)
    }

    /**
     * Detects the foreground document bounding box against a scanner lid or table background
     * and returns a normalized CropRect (0f..1f), or null if the subject already fills the frame.
     */
    fun detectEdgesRect(pixels: IntArray, width: Int, height: Int): CropRect? {
        if (width < 24 || height < 24 || pixels.size < width * height) return null

        // Sample border pixels to estimate background luminance
        var borderSum = 0L
        var borderCount = 0
        val step = max(1, min(width, height) / 40)
        for (x in 0 until width step step) {
            borderSum += luma(pixels[x])
            borderSum += luma(pixels[(height - 1) * width + x])
            borderCount += 2
        }
        for (y in 0 until height step step) {
            borderSum += luma(pixels[y * width])
            borderSum += luma(pixels[y * width + (width - 1)])
            borderCount += 2
        }
        val bgLuma = if (borderCount > 0) (borderSum / borderCount).toInt() else 245

        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0
        var fgCount = 0

        val scanStep = max(1, min(width, height) / 200)
        val diffThreshold = 22

        for (y in 0 until height step scanStep) {
            val rowOffset = y * width
            for (x in 0 until width step scanStep) {
                val lum = luma(pixels[rowOffset + x])
                if (abs(lum - bgLuma) >= diffThreshold) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                    fgCount++
                }
            }
        }

        if (fgCount < 16 || maxX <= minX || maxY <= minY) return null

        val padX = (width * 0.015f).roundToInt()
        val padY = (height * 0.015f).roundToInt()
        val left = (minX - padX).coerceAtLeast(0)
        val top = (minY - padY).coerceAtLeast(0)
        val right = (maxX + padX).coerceAtMost(width)
        val bottom = (maxY + padY).coerceAtMost(height)

        val normW = (right - left).toFloat() / width.toFloat()
        val normH = (bottom - top).toFloat() / height.toFloat()

        if (normW < 0.12f || normH < 0.12f) return null
        if (normW > 0.97f && normH > 0.97f) {
            return CropRect(0.03f, 0.03f, 0.94f, 0.94f)
        }
        return CropRect(
            x = left.toFloat() / width.toFloat(),
            y = top.toFloat() / height.toFloat(),
            width = normW,
            height = normH
        ).clamped()
    }

    fun detectEdgesRect(bitmap: Bitmap): CropRect? {
        val targetW = min(bitmap.width, 400).coerceAtLeast(32)
        val targetH = ((bitmap.height.toFloat() / bitmap.width.toFloat()) * targetW)
            .roundToInt().coerceAtLeast(32)
        val scaled = if (bitmap.width != targetW || bitmap.height != targetH) {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else {
            bitmap
        }
        val pixels = IntArray(targetW * targetH)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        if (scaled !== bitmap) scaled.recycle()
        return detectEdgesRect(pixels, targetW, targetH)
    }

    /**
     * Splits a two-page open book spread along the center spine into 2 upright pages.
     */
    fun splitBookSpread(bitmap: Bitmap): List<Bitmap> {
        val source = if (bitmap.height > bitmap.width) {
            rotateBitmap(bitmap, 90)
        } else {
            bitmap
        }
        val halfW = (source.width / 2).coerceAtLeast(1)
        val leftPage = Bitmap.createBitmap(source, 0, 0, halfW, source.height)
        val rightPage = Bitmap.createBitmap(source, halfW, 0, source.width - halfW, source.height)
        return listOf(leftPage, rightPage)
    }

    /**
     * Detects multiple separate receipts/photos on a flatbed or tabletop and splits them.
     * Falls back to 2-up horizontal/vertical split if items are closely packed.
     */
    fun splitMultiItems(bitmap: Bitmap): List<Bitmap> {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 100 || h < 100) return listOf(bitmap)

        // Downscale for fast projection profile segmentation
        val sw = 240
        val sh = ((h.toFloat() / w.toFloat()) * sw).roundToInt().coerceAtLeast(120)
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        small.recycle()

        var borderSum = 0L
        for (x in 0 until sw) {
            borderSum += luma(pixels[x])
            borderSum += luma(pixels[(sh - 1) * sw + x])
        }
        val bgLuma = (borderSum / (sw * 2)).toInt()

        // Horizontal valleys
        val rowCounts = IntArray(sh)
        for (y in 0 until sh) {
            var count = 0
            for (x in 0 until sw) {
                if (abs(luma(pixels[y * sw + x]) - bgLuma) > 24) count++
            }
            rowCounts[y] = count
        }

        val results = mutableListOf<Bitmap>()
        var inSegment = false
        var segStart = 0
        val minBand = (sh * 0.14f).roundToInt()

        for (y in 0 until sh) {
            val active = rowCounts[y] > (sw * 0.06f)
            if (active && !inSegment) {
                inSegment = true
                segStart = y
            } else if (!active && inSegment) {
                inSegment = false
                if (y - segStart >= minBand) {
                    val topPx = ((segStart.toFloat() / sh) * h).roundToInt().coerceIn(0, h - 1)
                    val bottomPx = ((y.toFloat() / sh) * h).roundToInt().coerceIn(topPx + 1, h)
                    val band = Bitmap.createBitmap(bitmap, 0, topPx, w, bottomPx - topPx)
                    val croppedRect = detectEdgesRect(band)
                    if (croppedRect != null) {
                        results.add(cropNormalized(band, croppedRect))
                        band.recycle()
                    } else {
                        results.add(band)
                    }
                }
            }
        }
        if (inSegment && sh - segStart >= minBand) {
            val topPx = ((segStart.toFloat() / sh) * h).roundToInt().coerceIn(0, h - 1)
            val band = Bitmap.createBitmap(bitmap, 0, topPx, w, h - topPx)
            val croppedRect = detectEdgesRect(band)
            if (croppedRect != null) {
                results.add(cropNormalized(band, croppedRect))
                band.recycle()
            } else {
                results.add(band)
            }
        }

        return if (results.size >= 2) results else listOf(bitmap)
    }

    /**
     * Stitches Front and Back ID card captures onto a single clean A4-proportioned printable canvas.
     */
    fun stitchIdCard(front: Bitmap, back: Bitmap): Bitmap {
        val canvasW = 1240
        val canvasH = 1754
        val result = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.rgb(210, 218, 226)
            strokeWidth = 3f
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(100, 116, 139)
            textSize = 28f
        }

        val maxCardW = (canvasW * 0.72f).roundToInt()
        val maxCardH = (canvasH * 0.34f).roundToInt()

        fun drawCard(card: Bitmap, centerY: Float, label: String) {
            val scale = min(
                maxCardW.toFloat() / card.width.toFloat(),
                maxCardH.toFloat() / card.height.toFloat()
            )
            val drawW = (card.width * scale).roundToInt()
            val drawH = (card.height * scale).roundToInt()
            val left = (canvasW - drawW) / 2
            val top = (centerY - drawH / 2f).roundToInt()
            val dest = Rect(left, top, left + drawW, top + drawH)
            canvas.drawText(label, left.toFloat(), (top - 18).toFloat(), labelPaint)
            canvas.drawBitmap(card, null, dest, paint)
            canvas.drawRect(RectF(dest), borderPaint)
        }

        drawCard(front, canvasH * 0.28f, "ID CARD — FRONT")
        drawCard(back, canvasH * 0.72f, "ID CARD — BACK")
        return result
    }

    /**
     * Non-destructive pipeline: applies Rotation -> Crop -> Tone/Color Filter -> Annotations.
     * Rotating before cropping ensures that when a user rotates a document and then crops it,
     * the crop coordinates match the rotated orientation shown in the editor.
     */
    fun applyEdits(source: Bitmap, edits: PageEdits): Bitmap {
        // 1. Rotate first
        val normRot = ((edits.rotation % 360) + 360) % 360
        var working = if (normRot != 0) {
            rotateBitmap(source, normRot)
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
        }

        // 2. Crop in the rotated image's coordinate system
        if (edits.crop != null) {
            val cropped = cropNormalized(working, edits.crop.clamped())
            if (cropped !== working && working !== source) working.recycle()
            working = cropped
        }

        // 3. Tone & Filter
        val hasTone = edits.brightness != 0f || edits.contrast != 0f || edits.saturation != 0f
        if (hasTone || edits.filter != "none") {
            val filtered = applyColorAndFilter(working, edits)
            if (filtered !== working && working !== source) working.recycle()
            working = filtered
        }

        // 4. Annotations (Pen, Highlighter, Text)
        if (edits.annotations.isNotEmpty()) {
            val mutable = if (working.isMutable) working else working.copy(Bitmap.Config.ARGB_8888, true)
            drawAnnotations(mutable, edits.annotations)
            working = mutable
        }

        return working
    }

    fun cropNormalized(source: Bitmap, crop: CropRect): Bitmap {
        val c = crop.clamped()
        val left = (c.x * source.width).roundToInt().coerceIn(0, source.width - 1)
        val top = (c.y * source.height).roundToInt().coerceIn(0, source.height - 1)
        val w = (c.width * source.width).roundToInt().coerceIn(1, source.width - left)
        val h = (c.height * source.height).roundToInt().coerceIn(1, source.height - top)
        return Bitmap.createBitmap(source, left, top, w, h)
    }

    fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun applyColorAndFilter(source: Bitmap, edits: PageEdits): Bitmap {
        val w = source.width
        val h = source.height

        if (edits.filter == "bw_document") {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(w * h)
            source.getPixels(pixels, 0, w, 0, 0, w, h)
            val hist = IntArray(256)
            val bOffset = (edits.brightness * 1.8f).roundToInt()
            for (i in pixels.indices) {
                val lum = (luma(pixels[i]) + bOffset).coerceIn(0, 255)
                hist[lum]++
            }
            val thresh = otsuThreshold(hist, pixels.size)
            for (i in pixels.indices) {
                val lum = (luma(pixels[i]) + bOffset).coerceIn(0, 255)
                pixels[i] = if (lum >= thresh) Color.WHITE else Color.BLACK
            }
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            return out
        }

        val cm = ColorMatrix()

        // Saturation / Grayscale
        when (edits.filter) {
            "grayscale" -> cm.setSaturation(0f)
            else -> {
                val sat = ((edits.saturation + 100f) / 100f).coerceIn(0f, 3f)
                cm.setSaturation(sat)
            }
        }

        // Contrast & Brightness (+ Auto-Enhance boost)
        val extraContrast = if (edits.filter == "auto_enhance") 24f else 0f
        val extraBrightness = if (edits.filter == "auto_enhance") 8f else 0f
        val cFactor = (((edits.contrast + extraContrast) + 100f) / 100f).coerceIn(0.2f, 3.0f)
        val bShift = (edits.brightness + extraBrightness) * 1.8f
        val translate = (-0.5f * cFactor + 0.5f) * 255f + bShift

        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                cFactor, 0f, 0f, 0f, translate,
                0f, cFactor, 0f, 0f, translate,
                0f, 0f, cFactor, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(contrastMatrix)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
    }

    private fun drawAnnotations(bitmap: Bitmap, strokes: List<AnnotationStroke>) {
        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        for (stroke in strokes) {
            val baseColor = stroke.colorHex.toInt()
            if (stroke.tool == "text" && !stroke.text.isNullOrBlank() && stroke.points.isNotEmpty()) {
                val pt = stroke.points.first()
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = baseColor
                    textSize = (stroke.size * (w / 420f)).coerceAtLeast(22f)
                    isFakeBoldText = true
                }
                canvas.drawText(stroke.text, pt.first * w, pt.second * h, textPaint)
            } else if (stroke.points.size >= 2) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    color = baseColor
                    alpha = if (stroke.tool == "highlighter") 105 else 255
                    strokeWidth = (stroke.size * (w / 500f)).coerceAtLeast(3f)
                }
                val path = Path()
                val first = stroke.points.first()
                path.moveTo(first.first * w, first.second * h)
                for (i in 1 until stroke.points.size) {
                    val pt = stroke.points[i]
                    path.lineTo(pt.first * w, pt.second * h)
                }
                canvas.drawPath(path, paint)
            }
        }
    }

    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }
}
