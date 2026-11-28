package com.almoukhtar.alprint.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesomeMotion
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.almoukhtar.alprint.model.DuplexMode
import com.almoukhtar.alprint.util.PdfAndOcrService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun PrintDocsScreen(
    docTitle: String,
    sourcePages: List<Bitmap>,
    onPickDocument: () -> Unit,
    onLoadFromScanSession: () -> Unit,
    hasScannedSessionPages: Boolean,
    onLogPrintJob: (String, String) -> Unit
) {
    val context = LocalContext.current
    var rangeMode by remember { mutableStateOf("all") } // "all" or "custom"
    var customRangeExpr by remember { mutableStateOf("1") }
    var pagesPerSheet by remember { mutableIntStateOf(1) } // 1, 2, 4
    var grayscale by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }
    var paperSize by remember { mutableStateOf("A4") }
    var fitMode by remember { mutableStateOf("fit") } // "fit" or "fill"
    var marginMode by remember { mutableStateOf("minimal") } // "minimal", "normal", "none"
    var scalePercent by remember { mutableFloatStateOf(100f) }
    var printDpi by remember { mutableIntStateOf(300) }
    var duplexMode by remember { mutableStateOf(DuplexMode.ONE_SIDED) }
    var manualFlipEdge by remember { mutableStateOf("long_edge") } // "long_edge" | "short_edge"
    var manualReversePass2 by remember { mutableStateOf(false) }

    val paperSizeOptions = listOf("A4", "Letter", "Legal", "4x6\"", "5x7\"", "8x10\"")
    val (sheetW, sheetH) = remember(paperSize, landscape) {
        PdfAndOcrService.getPaperDimensions(paperSize, landscape)
    }
    val sheetAspectRatio = remember(sheetW, sheetH) {
        sheetW.toFloat() / sheetH.toFloat().coerceAtLeast(1f)
    }
    val paperDimensionsLabel = remember(paperSize, landscape) {
        PdfAndOcrService.getPaperDimensionsLabel(paperSize, landscape)
    }

    var composedSheets by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var currentSheetIndex by remember { mutableIntStateOf(0) }

    // Guided Manual 2-Pass Duplex modal state
    var manualPass2Sheets by remember { mutableStateOf<List<Bitmap>?>(null) }
    var manualAddedBlankPage by remember { mutableStateOf(false) }
    var manualPass2OrderLabel by remember { mutableStateOf("") }
    var manualPass2Rotated180 by remember { mutableStateOf(true) }

    fun selectedSourceBitmaps(): List<Bitmap> {
        val indices = if (rangeMode == "custom") {
            PdfAndOcrService.parsePageRange(customRangeExpr, sourcePages.size)
        } else {
            sourcePages.indices.toList()
        }
        return indices.mapNotNull { sourcePages.getOrNull(it) }
    }

    LaunchedEffect(
        sourcePages,
        rangeMode,
        customRangeExpr,
        pagesPerSheet,
        grayscale,
        landscape,
        paperSize,
        fitMode,
        marginMode,
        scalePercent
    ) {
        if (sourcePages.isEmpty()) {
            composedSheets = emptyList()
            currentSheetIndex = 0
        } else {
            withContext(Dispatchers.Default) {
                val filtered = selectedSourceBitmaps()
                val sheets = PdfAndOcrService.composePrintSheets(
                    pages = filtered,
                    pagesPerSheet = pagesPerSheet,
                    grayscale = grayscale,
                    scalePercent = scalePercent.roundToInt(),
                    fitMode = fitMode,
                    landscape = landscape,
                    paperSize = paperSize,
                    marginMode = marginMode
                )
                composedSheets = sheets
                if (currentSheetIndex >= sheets.size) {
                    currentSheetIndex = (sheets.size - 1).coerceAtLeast(0)
                }
            }
        }
    }

    // Guided Manual 2-Pass Flip Modal (Step 2 of 2)
    val pass2 = manualPass2Sheets
    if (pass2 != null) {
        AlertDialog(
            onDismissRequest = { manualPass2Sheets = null },
            title = {
                Text(
                    text = "Manual 2-Pass Duplex — Step 2 of 2",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Pass 1 (Odd Front Pages) has been sent to the print spooler."
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = if (manualPass2Rotated180) {
                                    "Long Edge · Book Flip (Pass 2 rotated 180°)"
                                } else {
                                    "Short Edge · Notepad Flip (0°)"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "Pass 2 order: $manualPass2OrderLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    Text(
                        text = "1. Wait until all Pass 1 sheets finish printing.\n" +
                            "2. Lift the printed stack from the output tray as-is (do NOT reorder or turn pages left-to-right).\n" +
                            "3. Tip the stack straight back into the rear input tray with the printed side facing away (back) and the blank side facing you (the edge closest to the printer goes down into the tray).\n" +
                            "4. Tap 'Print Pass 2' below."
                    )
                    if (manualAddedBlankPage) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Smart Odd-Page Padding: Because your document has an odd number of sheets (${composedSheets.size}), AlPrint automatically appended 1 blank white back page to Pass 2 so you can reload the entire stack as-is without removing the last sheet!",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        PdfAndOcrService.printBitmapsWithAndroidSpooler(
                            context = context,
                            jobName = "${docTitle}_Pass2_Back",
                            sheets = pass2,
                            duplexMode = DuplexMode.ONE_SIDED,
                            grayscale = grayscale,
                            landscape = landscape,
                            paperSize = paperSize,
                            dpi = printDpi
                        )
                        onLogPrintJob(
                            "Manual Duplex Pass 2: $docTitle",
                            "${pass2.size} back sheet(s) sent to printer"
                        )
                        manualPass2Sheets = null
                    }
                ) {
                    Text("Print Pass 2 (${pass2.size} Back Sheet${if (pass2.size == 1) "" else "s"})")
                }
            },
            dismissButton = {
                TextButton(onClick = { manualPass2Sheets = null }) {
                    Text("Close")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Document Picker Card
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Print PDF & Documents",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onPickDocument,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open PDF / Image")
                    }
                    if (hasScannedSessionPages) {
                        OutlinedButton(
                            onClick = onLoadFromScanSession,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Use Scanned Pages")
                        }
                    }
                }
            }
        }

        // Live Composed Sheet Preview
        if (composedSheets.isNotEmpty()) {
            val activeSheet = composedSheets.getOrNull(currentSheetIndex) ?: composedSheets.first()
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = docTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Sheet ${currentSheetIndex + 1}/${composedSheets.size} • $paperDimensionsLabel • ${pagesPerSheet}-Up",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row {
                            IconButton(
                                onClick = { if (currentSheetIndex > 0) currentSheetIndex-- },
                                enabled = currentSheetIndex > 0
                            ) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Previous Sheet")
                            }
                            IconButton(
                                onClick = { if (currentSheetIndex < composedSheets.lastIndex) currentSheetIndex++ },
                                enabled = currentSheetIndex < composedSheets.lastIndex
                            ) {
                                Icon(Icons.Default.ArrowForward, contentDescription = "Next Sheet")
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F172A))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .then(
                                    if (landscape) {
                                        Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(sheetAspectRatio, matchHeightConstraintsFirst = false)
                                    } else {
                                        Modifier
                                            .fillMaxHeight()
                                            .aspectRatio(sheetAspectRatio, matchHeightConstraintsFirst = true)
                                    }
                                )
                                .shadow(8.dp, RoundedCornerShape(4.dp))
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White)
                                .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = activeSheet.asImageBitmap(),
                                contentDescription = "Sheet Preview",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Surface(
                            color = Color(0xCC0F172A),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.align(Alignment.BottomEnd)
                        ) {
                            Text(
                                text = "$paperSize • ${if (landscape) "Landscape" else "Portrait"} • ${scalePercent.roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // Two-Sided (Duplex) 2×2 Visual Button Grid with Icons
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Two-Sided (Duplex) Mode",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DuplexGridButton(
                        modifier = Modifier.weight(1f),
                        selected = duplexMode == DuplexMode.ONE_SIDED,
                        icon = Icons.Default.Description,
                        title = DuplexMode.ONE_SIDED.label,
                        subtitle = DuplexMode.ONE_SIDED.subtitle,
                        onClick = { duplexMode = DuplexMode.ONE_SIDED }
                    )
                    DuplexGridButton(
                        modifier = Modifier.weight(1f),
                        selected = duplexMode == DuplexMode.LONG_EDGE,
                        icon = Icons.Default.MenuBook,
                        title = DuplexMode.LONG_EDGE.label,
                        subtitle = DuplexMode.LONG_EDGE.subtitle,
                        onClick = { duplexMode = DuplexMode.LONG_EDGE }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DuplexGridButton(
                        modifier = Modifier.weight(1f),
                        selected = duplexMode == DuplexMode.SHORT_EDGE,
                        icon = Icons.Default.SwapVert,
                        title = DuplexMode.SHORT_EDGE.label,
                        subtitle = DuplexMode.SHORT_EDGE.subtitle,
                        onClick = { duplexMode = DuplexMode.SHORT_EDGE }
                    )
                    DuplexGridButton(
                        modifier = Modifier.weight(1f),
                        selected = duplexMode == DuplexMode.MANUAL_TWO_PASS,
                        icon = Icons.Default.Flip,
                        title = DuplexMode.MANUAL_TWO_PASS.label,
                        subtitle = "Guided 2-pass flip (ideal for HP Smart Tank 580)",
                        onClick = { duplexMode = DuplexMode.MANUAL_TWO_PASS }
                    )
                }
                if (duplexMode == DuplexMode.MANUAL_TWO_PASS) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Manual Flip Binding",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = manualFlipEdge == "long_edge",
                                onClick = { manualFlipEdge = "long_edge" },
                                label = { Text("Long Edge (Book · 180°)") }
                            )
                            FilterChip(
                                selected = manualFlipEdge == "short_edge",
                                onClick = { manualFlipEdge = "short_edge" },
                                label = { Text("Short Edge (Notepad · 0°)") }
                            )
                        }
                        FilterChip(
                            selected = manualReversePass2,
                            onClick = { manualReversePass2 = !manualReversePass2 },
                            label = { Text("Reverse Pass 2 Order (Front-Tray)") }
                        )
                    }
                }
            }
        }

        // Layout, Page Range, N-Up, Color & Scale Settings Card
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Page Range, N-Up & Color",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                // Page Range
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = rangeMode == "all",
                        onClick = { rangeMode = "all" },
                        label = { Text("All Pages (${sourcePages.size})") }
                    )
                    FilterChip(
                        selected = rangeMode == "custom",
                        onClick = { rangeMode = "custom" },
                        label = { Text("Custom Range") }
                    )
                }
                if (rangeMode == "custom") {
                    OutlinedTextField(
                        value = customRangeExpr,
                        onValueChange = { customRangeExpr = it },
                        label = { Text("Pages (e.g. 1-3, 5)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Paper Size Selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Paper:", style = MaterialTheme.typography.bodySmall)
                    for (sz in paperSizeOptions) {
                        FilterChip(
                            selected = paperSize == sz,
                            onClick = { paperSize = sz },
                            label = { Text(sz) }
                        )
                    }
                }

                // Pages Per Sheet (1 / 2 / 4 N-Up)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Pages / Sheet:", style = MaterialTheme.typography.bodySmall)
                    for (n in listOf(1, 2, 4)) {
                        FilterChip(
                            selected = pagesPerSheet == n,
                            onClick = { pagesPerSheet = n },
                            label = { Text("${n}-Up") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.AutoAwesomeMotion,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }

                // Margins Selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Margins:", style = MaterialTheme.typography.bodySmall)
                    FilterChip(
                        selected = marginMode == "minimal",
                        onClick = { marginMode = "minimal" },
                        label = { Text("Minimal") }
                    )
                    FilterChip(
                        selected = marginMode == "normal",
                        onClick = { marginMode = "normal" },
                        label = { Text("Standard") }
                    )
                    FilterChip(
                        selected = marginMode == "none",
                        onClick = { marginMode = "none" },
                        label = { Text("None") }
                    )
                }

                // Color & Orientation
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !grayscale,
                        onClick = { grayscale = false },
                        label = { Text("Color") }
                    )
                    FilterChip(
                        selected = grayscale,
                        onClick = { grayscale = true },
                        label = { Text("Black & White") }
                    )
                    FilterChip(
                        selected = !landscape,
                        onClick = { landscape = false },
                        label = { Text("Portrait") }
                    )
                    FilterChip(
                        selected = landscape,
                        onClick = { landscape = true },
                        label = { Text("Landscape") }
                    )
                }

                // Fit vs Fill & Custom Scale
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = fitMode == "fit",
                        onClick = { fitMode = "fit" },
                        label = { Text("Fit Printable Area") }
                    )
                    FilterChip(
                        selected = fitMode == "fill",
                        onClick = { fitMode = "fill" },
                        label = { Text("Fill Page") }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Scale (${scalePercent.roundToInt()}%)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(96.dp)
                    )
                    Slider(
                        value = scalePercent,
                        onValueChange = { scalePercent = it },
                        valueRange = 50f..100f,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { scalePercent = 100f }) {
                        Text("100%")
                    }
                }

                // Print Quality (DPI)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Quality:", style = MaterialTheme.typography.bodySmall)
                    for (dpiOption in listOf(150, 300, 600)) {
                        FilterChip(
                            selected = printDpi == dpiOption,
                            onClick = { printDpi = dpiOption },
                            label = { Text("$dpiOption DPI") }
                        )
                    }
                }

                // Print Button
                Button(
                    onClick = {
                        if (composedSheets.isEmpty()) return@Button
                        val composeDpi = minOf(printDpi, 300)
                        val hiResSheets = PdfAndOcrService.composePrintSheets(
                            pages = selectedSourceBitmaps(),
                            pagesPerSheet = pagesPerSheet,
                            grayscale = grayscale,
                            scalePercent = scalePercent.roundToInt(),
                            fitMode = fitMode,
                            landscape = landscape,
                            paperSize = paperSize,
                            marginMode = marginMode,
                            dpi = composeDpi
                        )
                        if (duplexMode == DuplexMode.MANUAL_TWO_PASS) {
                            val rotatePass2 = (manualFlipEdge == "long_edge")
                            val (_, pass2Order) = PdfAndOcrService.computeManualDuplexOrder(
                                totalSheets = hiResSheets.size,
                                reversePass2 = manualReversePass2
                            )
                            val (pass1Odd, pass2Even) = PdfAndOcrService.splitManualDuplexPasses(
                                sheets = hiResSheets,
                                rotatePass2_180 = rotatePass2,
                                reversePass2 = manualReversePass2
                            )
                            manualAddedBlankPage = (hiResSheets.size % 2 == 1)
                            manualPass2OrderLabel = pass2Order.joinToString(", ") { idx ->
                                if (idx == null) "Blank" else "${idx + 1}"
                            }
                            manualPass2Rotated180 = rotatePass2
                            manualPass2Sheets = pass2Even
                            PdfAndOcrService.printBitmapsWithAndroidSpooler(
                                context = context,
                                jobName = "${docTitle}_Pass1_Front",
                                sheets = pass1Odd,
                                duplexMode = DuplexMode.ONE_SIDED,
                                grayscale = grayscale,
                                landscape = landscape,
                                paperSize = paperSize,
                                dpi = printDpi
                            )
                            onLogPrintJob(
                                "Manual Duplex Pass 1: $docTitle",
                                "${pass1Odd.size} front sheet(s) • $paperSize • $printDpi DPI"
                            )
                        } else {
                            PdfAndOcrService.printBitmapsWithAndroidSpooler(
                                context = context,
                                jobName = docTitle,
                                sheets = hiResSheets,
                                duplexMode = duplexMode,
                                grayscale = grayscale,
                                landscape = landscape,
                                paperSize = paperSize,
                                dpi = printDpi,
                                onRenderForPageSize = { targetSize, targetLand, targetMono ->
                                    PdfAndOcrService.composePrintSheets(
                                        pages = selectedSourceBitmaps(),
                                        pagesPerSheet = pagesPerSheet,
                                        grayscale = targetMono,
                                        scalePercent = scalePercent.roundToInt(),
                                        fitMode = fitMode,
                                        landscape = targetLand,
                                        paperSize = targetSize,
                                        marginMode = marginMode,
                                        dpi = composeDpi
                                    )
                                }
                            )
                            onLogPrintJob(
                                "Print Document: $docTitle",
                                "${hiResSheets.size} sheet(s) • $paperSize • $printDpi DPI • ${duplexMode.label}"
                            )
                        }
                    },
                    enabled = composedSheets.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (duplexMode == DuplexMode.MANUAL_TWO_PASS) {
                            "Start Guided 2-Pass Duplex (${composedSheets.size} Sheet${if (composedSheets.size == 1) "" else "s"})"
                        } else {
                            "Print with Android Wi-Fi Spooler (${composedSheets.size} Sheet${if (composedSheets.size == 1) "" else "s"})"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DuplexGridButton(
    modifier: Modifier = Modifier,
    selected: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val bgColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
