package com.almoukhtar.alprint

import com.almoukhtar.alprint.model.CropRect
import com.almoukhtar.alprint.model.FilenameFormatter
import com.almoukhtar.alprint.util.ImageProcessor
import com.almoukhtar.alprint.util.PdfAndOcrService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageProcessorTest {

    @Test
    fun testOtsuThresholdSeparatesBimodalHistogram() {
        val hist = IntArray(256)
        hist[35] = 400 // dark ink pixels
        hist[230] = 1600 // white paper pixels
        val threshold = ImageProcessor.otsuThreshold(hist, 2000)
        assertTrue("Expected Otsu threshold between 35 and 230, got $threshold", threshold in 35..220)
    }

    @Test
    fun testDetectEdgesFindsDocumentInsideWhiteLidBackground() {
        val w = 100
        val h = 100
        val pixels = IntArray(w * h) { (0xFF shl 24) or (250 shl 16) or (250 shl 8) or 250 }

        // Draw a darker document rectangle from (20, 15) to (75, 80)
        for (y in 15..80) {
            for (x in 20..75) {
                pixels[y * w + x] = (0xFF shl 24) or (60 shl 16) or (65 shl 8) or 70
            }
        }

        val rect: CropRect? = ImageProcessor.detectEdgesRect(pixels, w, h)
        assertNotNull("Expected a non-null detected CropRect", rect)
        rect!!
        assertTrue("Expected x around 0.20, got ${rect.x}", rect.x in 0.15f..0.25f)
        assertTrue("Expected y around 0.15, got ${rect.y}", rect.y in 0.10f..0.20f)
        assertTrue("Expected width around 0.55, got ${rect.width}", rect.width in 0.50f..0.65f)
        assertTrue("Expected height around 0.65, got ${rect.height}", rect.height in 0.60f..0.75f)
    }

    @Test
    fun testFilenameFormatterReplacesTokensCleanly() {
        val formatted = FilenameFormatter.format("Scan_{preset}_{date}", "ID Card", 1700000000000L)
        assertTrue(formatted.startsWith("Scan_ID_Card_"))
    }

    @Test
    fun testParsePageRangeHandlesRangesAndCommas() {
        val pages = PdfAndOcrService.parsePageRange("1-3, 5", 6)
        assertEquals(listOf(0, 1, 2, 4), pages)
    }

    @Test
    fun testPaperDimensionsChangeWithSizeAndOrientation() {
        val p4x6 = PdfAndOcrService.getPaperDimensions("4x6\"", landscape = false)
        val l4x6 = PdfAndOcrService.getPaperDimensions("4x6\"", landscape = true)
        val p8x10 = PdfAndOcrService.getPaperDimensions("8x10\"", landscape = false)
        val pA4 = PdfAndOcrService.getPaperDimensions("A4", landscape = false)

        assertTrue("Portrait 4x6 should be taller than wide", p4x6.first < p4x6.second)
        assertTrue("Landscape 4x6 should be wider than tall", l4x6.first > l4x6.second)
        assertEquals(p4x6.first, l4x6.second)
        assertEquals(p4x6.second, l4x6.first)

        val ratio4x6 = p4x6.first.toFloat() / p4x6.second.toFloat()
        val ratio8x10 = p8x10.first.toFloat() / p8x10.second.toFloat()
        val ratioA4 = pA4.first.toFloat() / pA4.second.toFloat()
        assertTrue("4x6 (0.667), A4 (0.707), and 8x10 (0.800) must have distinct aspect ratios", ratio4x6 < ratioA4 && ratioA4 < ratio8x10)
    }

    @Test
    fun testResolveEsclJobUrlRewritesMdnsHostToIpv4() {
        val base = "http://192.168.0.58/eSCL"
        val fromMdns = com.almoukhtar.alprint.network.NetworkPrinterService.resolveEsclJobUrl(
            base,
            "http://HP91C060.local/eSCL/ScanJobs/49483b60-1111"
        )
        assertEquals("http://192.168.0.58/eSCL/ScanJobs/49483b60-1111", fromMdns)

        val fromMdnsPort = com.almoukhtar.alprint.network.NetworkPrinterService.resolveEsclJobUrl(
            base,
            "http://HP91C060.local:8080/eSCL/ScanJobs/49483b60-2222/"
        )
        assertEquals("http://192.168.0.58:8080/eSCL/ScanJobs/49483b60-2222", fromMdnsPort)

        val fromRelative = com.almoukhtar.alprint.network.NetworkPrinterService.resolveEsclJobUrl(
            base,
            "/eSCL/ScanJobs/49483b60-3333"
        )
        assertEquals("http://192.168.0.58/eSCL/ScanJobs/49483b60-3333", fromRelative)

        val region4x6 = com.almoukhtar.alprint.network.NetworkPrinterService.getEsclScanRegionThreeHundredths("4x6\"")
        val regionA4 = com.almoukhtar.alprint.network.NetworkPrinterService.getEsclScanRegionThreeHundredths("A4")
        assertEquals(1200 to 1800, region4x6)
        assertEquals(2480 to 3508, regionA4)
    }

    @Test
    fun testPaperPointsMatchStandardSizes() {
        assertEquals(288 to 432, PdfAndOcrService.getPaperPoints("4x6\"", landscape = false))
        assertEquals(432 to 288, PdfAndOcrService.getPaperPoints("4x6\"", landscape = true))
        assertEquals(360 to 504, PdfAndOcrService.getPaperPoints("5x7\"", landscape = false))
        assertEquals(576 to 720, PdfAndOcrService.getPaperPoints("8x10\"", landscape = false))
        assertEquals(595 to 842, PdfAndOcrService.getPaperPoints("A4", landscape = false))
        assertEquals(612 to 792, PdfAndOcrService.getPaperPoints("Letter", landscape = false))
        assertEquals(612 to 1008, PdfAndOcrService.getPaperPoints("Legal", landscape = false))
        assertEquals(1008 to 612, PdfAndOcrService.getPaperPoints("Legal", landscape = true))

        val a4150 = PdfAndOcrService.getPaperDimensions("A4", landscape = false, dpi = 150)
        val a4300 = PdfAndOcrService.getPaperDimensions("A4", landscape = false, dpi = 300)
        assertEquals(a4150.first * 2, a4300.first)
        assertEquals(a4150.second * 2, a4300.second)
    }

    @Test
    fun testComputeManualDuplexOrderForRearTrayAndReversed() {
        // 4 sheets, rear-tray forward Pass 2: Pass 1 = [0, 2], Pass 2 = [1, 3]
        val (p1Even, p2Even) = PdfAndOcrService.computeManualDuplexOrder(4, reversePass2 = false)
        assertEquals(listOf(0, 2), p1Even)
        assertEquals(listOf<Int?>(1, 3), p2Even)

        // 5 sheets, rear-tray forward Pass 2: Pass 1 = [0, 2, 4], Pass 2 = [1, 3, null]
        val (p1Odd, p2Odd) = PdfAndOcrService.computeManualDuplexOrder(5, reversePass2 = false)
        assertEquals(listOf(0, 2, 4), p1Odd)
        assertEquals(listOf<Int?>(1, 3, null), p2Odd)

        // 5 sheets, reversed Pass 2 (front-cassette / face-down): Pass 1 = [0, 2, 4], Pass 2 = [null, 3, 1]
        val (p1Rev, p2Rev) = PdfAndOcrService.computeManualDuplexOrder(5, reversePass2 = true)
        assertEquals(listOf(0, 2, 4), p1Rev)
        assertEquals(listOf<Int?>(null, 3, 1), p2Rev)

        // 0 sheets: empty
        val (p1Zero, p2Zero) = PdfAndOcrService.computeManualDuplexOrder(0, reversePass2 = false)
        assertTrue(p1Zero.isEmpty())
        assertTrue(p2Zero.isEmpty())
    }
}
