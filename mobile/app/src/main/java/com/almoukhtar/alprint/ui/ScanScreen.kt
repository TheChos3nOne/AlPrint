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
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Scanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.almoukhtar.alprint.model.OcrResult
import com.almoukhtar.alprint.model.PageEdits
import com.almoukhtar.alprint.model.ScanInputSource
import com.almoukhtar.alprint.model.ScanPage
import com.almoukhtar.alprint.model.ScanPreset

@Composable
fun ScanScreen(
    inputSource: ScanInputSource,
    onSelectInputSource: (ScanInputSource) -> Unit,
    activePreset: ScanPreset,
    onSelectPreset: (ScanPreset) -> Unit,
    detectEdgesEnabled: Boolean,
    onToggleDetectEdges: (Boolean) -> Unit,
    wifiDpi: Int,
    onSelectWifiDpi: (Int) -> Unit,
    wifiColorMode: String,
    onSelectWifiColorMode: (String) -> Unit,
    wifiPageSize: String,
    onSelectWifiPageSize: (String) -> Unit,
    printerIp: String,
    isScanning: Boolean,
    scanStatusText: String,
    onCancelScan: () -> Unit,
    idCardFrontBitmap: Bitmap?,
    onCancelIdCardStep: () -> Unit,
    pages: List<ScanPage>,
    pageBitmaps: Map<String, Bitmap>,
    originalBitmaps: Map<String, Bitmap>,
    ocrResults: Map<String, OcrResult>,
    postScanCropPageId: String?,
    onDismissPostScanCrop: () -> Unit,
    onLaunchPhoneCameraScanner: () -> Unit,
    onLaunchFallbackCamera: () -> Unit,
    onLaunchWifiFlatbedScan: () -> Unit,
    onLaunchImportFiles: () -> Unit,
    onUpdatePageEdits: (String, PageEdits) -> Unit,
    onRotatePage90: (String) -> Unit,
    onMovePage: (Int, Int) -> Unit,
    onDeletePage: (String) -> Unit,
    onClearAllPages: () -> Unit,
    onRunOcrForPage: (String) -> Unit,
    onExportPdf: (Boolean) -> Unit,
    onExportJpeg: (String) -> Unit,
    onPrintScannedPages: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val mainScrollState = rememberScrollState()
    var scannedCardOffsetY by remember { mutableIntStateOf(0) }
    var previousPageCount by remember { mutableIntStateOf(pages.size) }

    var selectedPageIndex by remember(pages.size) {
        mutableIntStateOf((pages.size - 1).coerceAtLeast(0))
    }
    val currentPage = pages.getOrNull(selectedPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
    var editingPageId by remember { mutableStateOf<String?>(null) }
    var editingStartInCrop by remember { mutableStateOf(false) }

    // Auto-scroll smoothly down to the newly scanned document whenever a new page is added
    LaunchedEffect(pages.size) {
        if (pages.size > previousPageCount) {
            selectedPageIndex = pages.lastIndex
            kotlinx.coroutines.delay(100)
            val targetScroll = if (scannedCardOffsetY > 0) {
                scannedCardOffsetY
            } else {
                mainScrollState.maxValue
            }
            mainScrollState.animateScrollTo(targetScroll)
        }
        previousPageCount = pages.size
    }

    // If post-scan Detect Edges (Crop Box) triggered on a newly added page, open the 8-handle Crop Dialog
    val activeDialogPageId = postScanCropPageId ?: editingPageId
    val dialogPage = pages.firstOrNull { it.id == activeDialogPageId }
    val dialogOriginalBmp = dialogPage?.let { originalBitmaps[it.id] ?: pageBitmaps[it.id] }

    if (dialogPage != null && dialogOriginalBmp != null) {
        val isPostScanPrompt = postScanCropPageId != null
        PageEditorDialog(
            pageTitle = dialogPage.title,
            originalBitmap = dialogOriginalBmp,
            initialEdits = dialogPage.edits,
            startInCropMode = isPostScanPrompt || editingStartInCrop,
            isPostScanEdgePrompt = isPostScanPrompt,
            onDismiss = {
                if (isPostScanPrompt) onDismissPostScanCrop()
                editingPageId = null
            },
            onKeepFullPage = {
                // Keep uncropped full page
                onUpdatePageEdits(dialogPage.id, dialogPage.edits.copy(crop = null))
                onDismissPostScanCrop()
                editingPageId = null
            },
            onSave = { newEdits ->
                onUpdatePageEdits(dialogPage.id, newEdits)
                if (isPostScanPrompt) onDismissPostScanCrop()
                editingPageId = null
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(mainScrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Input Source Selector (Phone Camera / Wi-Fi Flatbed / Import)
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "1. Select Capture Source",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = inputSource == ScanInputSource.CAMERA,
                        onClick = { onSelectInputSource(ScanInputSource.CAMERA) },
                        label = { Text("Phone Camera", maxLines = 1) },
                        leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = inputSource == ScanInputSource.WIFI_ESCL,
                        onClick = { onSelectInputSource(ScanInputSource.WIFI_ESCL) },
                        label = { Text("Wi-Fi Flatbed", maxLines = 1) },
                        leadingIcon = { Icon(Icons.Default.Scanner, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = inputSource == ScanInputSource.IMPORT,
                        onClick = { onSelectInputSource(ScanInputSource.IMPORT) },
                        label = { Text("Import Gallery Photos or PDF", maxLines = 1) },
                        leadingIcon = { Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }

                // Wi-Fi Flatbed settings when WIFI_ESCL is active (split into 2 clean rows so nothing is cut off)
                if (inputSource == ScanInputSource.WIFI_ESCL) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("DPI:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(42.dp))
                        for (dpi in listOf(150, 300, 600)) {
                            FilterChip(
                                selected = wifiDpi == dpi,
                                onClick = { onSelectWifiDpi(dpi) },
                                label = { Text("$dpi") }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Mode:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(42.dp))
                        for (mode in listOf("Color", "Grayscale", "Black & White")) {
                            FilterChip(
                                selected = wifiColorMode == mode,
                                onClick = { onSelectWifiColorMode(mode) },
                                label = { Text(if (mode == "Black & White") "B&W" else mode, maxLines = 1) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Size:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(42.dp))
                        for (sz in listOf("A4", "Letter", "4x6\"")) {
                            FilterChip(
                                selected = wifiPageSize == sz,
                                onClick = { onSelectWifiPageSize(sz) },
                                label = { Text(sz, maxLines = 1) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(42.dp))
                        for (sz in listOf("5x7\"", "ID Card", "Full Glass")) {
                            FilterChip(
                                selected = wifiPageSize == sz,
                                onClick = { onSelectWifiPageSize(sz) },
                                label = { Text(sz, maxLines = 1) }
                            )
                        }
                    }
                }
            }
        }

        // 2. Scan Preset Selector + Helper Description + Detect Edges Toggle
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "2. Scan Mode Preset",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (preset in listOf(ScanPreset.DOCUMENT, ScanPreset.PHOTO, ScanPreset.ID_CARD)) {
                        FilterChip(
                            selected = activePreset == preset,
                            onClick = { onSelectPreset(preset) },
                            label = { Text(preset.label, maxLines = 1) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (preset in listOf(ScanPreset.MULTI_ITEM, ScanPreset.BOOK)) {
                        FilterChip(
                            selected = activePreset == preset,
                            onClick = { onSelectPreset(preset) },
                            label = { Text(preset.label, maxLines = 1) }
                        )
                    }
                }

                // 1-Line Helper Description Banner under Active Preset
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = activePreset.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }

                // Guided 2-Step ID Card Status Banner
                if (activePreset == ScanPreset.ID_CARD) {
                    Surface(
                        color = if (idCardFrontBitmap == null) Color(0xFF1E3A8A).copy(alpha = 0.2f)
                        else Color(0xFF065F46).copy(alpha = 0.25f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (idCardFrontBitmap == null) {
                                        "ID Card Step 1 of 2: Capture FRONT Side"
                                    } else {
                                        "ID Card Step 2 of 2: Flip Card & Capture BACK Side"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (idCardFrontBitmap == null) {
                                        "Scan or photograph the front of the ID card first."
                                    } else {
                                        "Front side captured! Now flip the ID card and capture the back to auto-stitch onto 1 page."
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (idCardFrontBitmap != null) {
                                TextButton(onClick = onCancelIdCardStep) {
                                    Text("Reset")
                                }
                            }
                        }
                    }
                }

                // Detect Edges (Crop Box) Toggle — Off by default; opens interactive 8-handle Crop Box when ON
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Detect Edges (Adjustable Crop Box)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Opens an adjustable 8-handle crop box after scan instead of cutting edges automatically",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = detectEdgesEnabled,
                        onCheckedChange = onToggleDetectEdges
                    )
                }

                // Primary Capture Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (inputSource) {
                        ScanInputSource.CAMERA -> {
                            Button(
                                onClick = onLaunchPhoneCameraScanner,
                                enabled = !isScanning,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    when {
                                        activePreset == ScanPreset.ID_CARD && idCardFrontBitmap == null ->
                                            "Scan ID Front (Camera)"
                                        activePreset == ScanPreset.ID_CARD && idCardFrontBitmap != null ->
                                            "Scan ID Back & Stitch"
                                        else -> "Scan with Phone Camera"
                                    }
                                )
                            }
                            OutlinedButton(
                                onClick = onLaunchFallbackCamera,
                                enabled = !isScanning
                            ) {
                                Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Quick Snap")
                            }
                        }

                        ScanInputSource.WIFI_ESCL -> {
                            Button(
                                onClick = onLaunchWifiFlatbedScan,
                                enabled = !isScanning,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isScanning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Icon(Icons.Default.Scanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    when {
                                        isScanning -> scanStatusText.ifBlank { "Scanning from Flatbed..." }
                                        activePreset == ScanPreset.ID_CARD && idCardFrontBitmap == null ->
                                            "Scan ID Front ($wifiPageSize • $wifiDpi DPI)"
                                        activePreset == ScanPreset.ID_CARD && idCardFrontBitmap != null ->
                                            "Scan ID Back & Stitch ($wifiPageSize)"
                                        else -> "Start Wi-Fi Flatbed Scan ($wifiDpi DPI • $wifiPageSize)"
                                    }
                                )
                            }
                            if (isScanning) {
                                OutlinedButton(onClick = onCancelScan) {
                                    Text("Cancel")
                                }
                            }
                        }

                        ScanInputSource.IMPORT -> {
                            Button(
                                onClick = onLaunchImportFiles,
                                enabled = !isScanning,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Choose Photos or PDF from Device")
                            }
                        }
                    }
                }
            }
        }

        // 3. Multi-Page Scanned Session Workspace
        if (pages.isNotEmpty() && currentPage != null) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        scannedCardOffsetY = coords.positionInParent().y.toInt()
                    }
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Scanned Pages (${pages.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    when (inputSource) {
                                        ScanInputSource.WIFI_ESCL -> onLaunchWifiFlatbedScan()
                                        ScanInputSource.IMPORT -> onLaunchImportFiles()
                                        ScanInputSource.CAMERA -> onLaunchPhoneCameraScanner()
                                    }
                                },
                                enabled = !isScanning
                            ) {
                                Text("+ Add Page")
                            }
                            TextButton(onClick = onClearAllPages) {
                                Text("Clear All")
                            }
                        }
                    }

                    // Active Page Large Preview Card
                    val activeBmp = pageBitmaps[currentPage.id]
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F172A)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (activeBmp != null) {
                            Image(
                                bitmap = activeBmp.asImageBitmap(),
                                contentDescription = currentPage.title,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(10.dp)
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                    }

                    // Quick Per-Page Toolbar (Rotate 90°, Crop Box, Edit/Markup, Offline OCR, Delete)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { onRotatePage90(currentPage.id) }) {
                            Icon(Icons.Default.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Rotate 90°")
                        }
                        OutlinedButton(
                            onClick = {
                                editingStartInCrop = true
                                editingPageId = currentPage.id
                            }
                        ) {
                            Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Crop Box")
                        }
                        OutlinedButton(
                            onClick = {
                                editingStartInCrop = false
                                editingPageId = currentPage.id
                            }
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Filters & Markup")
                        }
                        OutlinedButton(onClick = { onRunOcrForPage(currentPage.id) }) {
                            Icon(Icons.Default.TextSnippet, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Offline OCR")
                        }
                        IconButton(
                            onClick = {
                                if (selectedPageIndex > 0) {
                                    onMovePage(selectedPageIndex, selectedPageIndex - 1)
                                    selectedPageIndex -= 1
                                }
                            },
                            enabled = selectedPageIndex > 0
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Move Left")
                        }
                        IconButton(
                            onClick = {
                                if (selectedPageIndex < pages.lastIndex) {
                                    onMovePage(selectedPageIndex, selectedPageIndex + 1)
                                    selectedPageIndex += 1
                                }
                            },
                            enabled = selectedPageIndex < pages.lastIndex
                        ) {
                            Icon(Icons.Default.ArrowForward, contentDescription = "Move Right")
                        }
                        IconButton(onClick = { onDeletePage(currentPage.id) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete Page",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    // Page Thumbnail Filmstrip
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        pages.forEachIndexed { idx, page ->
                            val thumb = pageBitmaps[page.id]
                            val isSelected = idx == selectedPageIndex
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(84.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable { selectedPageIndex = idx }
                                    .padding(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(68.dp, 86.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF0F172A)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (thumb != null) {
                                        Image(
                                            bitmap = thumb.asImageBitmap(),
                                            contentDescription = page.title,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Page ${idx + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    // Offline OCR Extracted Text Drawer (if OCR was run on this page)
                    val ocr = ocrResults[currentPage.id]
                    if (ocr != null) {
                        Surface(
                            tonalElevation = 2.dp,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Offline OCR Text (${ocr.words.size} words • Smart Title: ${ocr.suggestedTitle})",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    TextButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(ocr.fullText))
                                        }
                                    ) {
                                        Text("Copy Text")
                                    }
                                }
                                Text(
                                    text = ocr.fullText.ifBlank { "No text detected on this page." },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    // Export & Print Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onExportPdf(false) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save PDF")
                        }
                        OutlinedButton(
                            onClick = { onExportPdf(true) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share PDF")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onExportJpeg(currentPage.id) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save Page JPEG")
                        }
                        Button(
                            onClick = onPrintScannedPages,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Print Pages")
                        }
                    }
                }
            }
        }
    }
}
