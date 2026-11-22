package com.almoukhtar.alprint.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Scanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.almoukhtar.alprint.model.ActivityEntry
import com.almoukhtar.alprint.model.DiscoveredDevice
import com.almoukhtar.alprint.model.FilenameFormatter
import com.almoukhtar.alprint.model.MobileTab
import com.almoukhtar.alprint.model.OcrResult
import com.almoukhtar.alprint.model.PageEdits
import com.almoukhtar.alprint.model.PrinterNetworkStatus
import com.almoukhtar.alprint.model.ScanInputSource
import com.almoukhtar.alprint.model.ScanPage
import com.almoukhtar.alprint.model.ScanPreset
import com.almoukhtar.alprint.model.ShortcutConfig
import com.almoukhtar.alprint.network.NetworkPrinterService
import com.almoukhtar.alprint.util.AppStorage
import com.almoukhtar.alprint.util.ImageProcessor
import com.almoukhtar.alprint.util.PdfAndOcrService
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private val AlPrintDarkScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF082F49),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFE0F2FE),
    background = Color(0xFF0B1120),
    surface = Color(0xFF111827),
    surfaceVariant = Color(0xFF1E293B)
)

private val AlPrintLightScheme = lightColorScheme(
    primary = Color(0xFF1D4ED8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE2E8F0)
)

/**
 * Process-wide reactive session store so that screen rotations, multi-window resizing,
 * or Activity recreations never lose scanned pages, bitmaps, OCR results, or print queues.
 */
object AlPrintSessionStore {
    var activeTab by mutableStateOf(MobileTab.HOME)
    var inputSource by mutableStateOf(ScanInputSource.CAMERA)
    var activePreset by mutableStateOf(ScanPreset.DOCUMENT)
    var detectEdgesEnabled by mutableStateOf(false)
    var wifiDpi by mutableIntStateOf(300)
    var wifiColorMode by mutableStateOf("Color")
    var wifiPageSize by mutableStateOf("A4")
    var idCardFrontBitmap by mutableStateOf<Bitmap?>(null)
    var pendingShortcut by mutableStateOf<ShortcutConfig?>(null)
    var postScanCropPageId by mutableStateOf<String?>(null)

    val scanPages = mutableStateListOf<ScanPage>()
    val originalBitmaps = mutableStateMapOf<String, Bitmap>()
    val processedBitmaps = mutableStateMapOf<String, Bitmap>()
    val ocrResults = mutableStateMapOf<String, OcrResult>()

    var printDocTitle by mutableStateOf("Document")
    var printDocBitmaps by mutableStateOf<List<Bitmap>>(emptyList())
    var printPhotoBitmaps by mutableStateOf<List<Bitmap>>(emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlPrintApp(
    sharedUris: List<Uri> = emptyList(),
    onConsumedSharedUris: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val storage = remember { AppStorage(context) }

    var darkMode by remember { mutableStateOf(storage.isDarkMode()) }
    var activeTab by AlPrintSessionStore::activeTab

    // Wi-Fi Printer & Scanner State
    var printerIp by remember { mutableStateOf(storage.getPrinterIp()) }
    var printerStatus by remember { mutableStateOf<PrinterNetworkStatus?>(null) }
    var discoveredDevices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var isProbingNetwork by remember { mutableStateOf(false) }

    // Activity & Shortcuts State
    var activities by remember { mutableStateOf<List<ActivityEntry>>(storage.loadActivities()) }
    var shortcuts by remember { mutableStateOf<List<ShortcutConfig>>(storage.loadShortcuts()) }

    // Scan Workspace State (backed by AlPrintSessionStore so rotation never resets anything)
    var inputSource by AlPrintSessionStore::inputSource
    var activePreset by AlPrintSessionStore::activePreset
    var detectEdgesEnabled by AlPrintSessionStore::detectEdgesEnabled
    var wifiDpi by AlPrintSessionStore::wifiDpi
    var wifiColorMode by AlPrintSessionStore::wifiColorMode
    var wifiPageSize by AlPrintSessionStore::wifiPageSize
    var isScanning by remember { mutableStateOf(false) }
    var scanStatusText by remember { mutableStateOf("") }
    var activeScanJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var idCardFrontBitmap by AlPrintSessionStore::idCardFrontBitmap
    var pendingShortcut by AlPrintSessionStore::pendingShortcut
    var postScanCropPageId by AlPrintSessionStore::postScanCropPageId

    val scanPages = AlPrintSessionStore.scanPages
    val originalBitmaps = AlPrintSessionStore.originalBitmaps
    val processedBitmaps = AlPrintSessionStore.processedBitmaps
    val ocrResults = AlPrintSessionStore.ocrResults

    // Print Docs & Photos Workspace State
    var printDocTitle by AlPrintSessionStore::printDocTitle
    var printDocBitmaps by AlPrintSessionStore::printDocBitmaps
    var printPhotoBitmaps by AlPrintSessionStore::printPhotoBitmaps

    fun logActivity(type: String, title: String, subtitle: String) {
        activities = storage.addActivity(type, title, subtitle)
    }

    fun probeNetwork() {
        scope.launch {
            isProbingNetwork = true
            printerStatus = NetworkPrinterService.probePrinterStatus(printerIp)
            val found = NetworkPrinterService.discoverLanDevices(context, printerIp)
            if (found.isNotEmpty()) {
                discoveredDevices = found
            }
            isProbingNetwork = false
        }
    }

    LaunchedEffect(Unit) {
        probeNetwork()
    }

    // Ingest newly captured bitmaps into the Scan session (handling ID Card 2-step stitch, Book split, Multi-Item split, and Detect Edges crop box)
    fun ingestCapturedBitmaps(rawBitmaps: List<Bitmap>, sourceLabel: String) {
        if (rawBitmaps.isEmpty()) return
        scope.launch {
            val activeSc = pendingShortcut
            val effectivePreset = activeSc?.preset ?: activePreset
            val defaultFilter = when {
                activeSc != null -> activeSc.filter
                effectivePreset == ScanPreset.DOCUMENT -> "auto_enhance"
                else -> "none"
            }

            // Guided 2-Step ID Card Front + Back Mode
            if (effectivePreset == ScanPreset.ID_CARD && rawBitmaps.size == 1) {
                val single = rawBitmaps.first()
                val front = idCardFrontBitmap
                if (front == null) {
                    idCardFrontBitmap = single
                    Toast.makeText(
                        context,
                        "ID Card Front captured! Flip the card and capture the Back side.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                } else {
                    val stitched = withContext(Dispatchers.Default) {
                        ImageProcessor.stitchIdCard(front, single)
                    }
                    idCardFrontBitmap = null
                    val pageId = "page_${System.currentTimeMillis()}"
                    val edits = PageEdits(filter = defaultFilter)
                    val processed = withContext(Dispatchers.Default) {
                        ImageProcessor.applyEdits(stitched, edits)
                    }
                    originalBitmaps[pageId] = stitched
                    processedBitmaps[pageId] = processed
                    scanPages.add(
                        ScanPage(
                            id = pageId,
                            title = "ID Card Front + Back",
                            originalFilePath = "",
                            processedFilePath = "",
                            width = processed.width,
                            height = processed.height,
                            edits = edits,
                            sourceLabel = sourceLabel
                        )
                    )
                    logActivity("scan", "ID Card Front + Back Stitched", "Source: $sourceLabel")
                    pendingShortcut = null
                    return@launch
                }
            }

            // Expand Book Spread or Multi-Item Split presets
            val expandedBitmaps = withContext(Dispatchers.Default) {
                val out = mutableListOf<Bitmap>()
                for (bmp in rawBitmaps) {
                    when (effectivePreset) {
                        ScanPreset.BOOK -> out.addAll(ImageProcessor.splitBookSpread(bmp))
                        ScanPreset.MULTI_ITEM -> out.addAll(ImageProcessor.splitMultiItems(bmp))
                        else -> out.add(bmp)
                    }
                }
                out
            }

            var lastAddedId: String? = null
            for ((idx, bmp) in expandedBitmaps.withIndex()) {
                val pageId = "page_${System.currentTimeMillis()}_$idx"
                val detectedRect = if (detectEdgesEnabled) {
                    withContext(Dispatchers.Default) { ImageProcessor.detectEdgesRect(bmp) }
                } else {
                    null
                }
                val edits = PageEdits(
                    filter = defaultFilter,
                    crop = null, // Never destructively auto-crop; open interactive 8-handle Crop Box instead!
                    suggestedCrop = detectedRect
                )
                val processed = withContext(Dispatchers.Default) {
                    ImageProcessor.applyEdits(bmp, edits)
                }
                originalBitmaps[pageId] = bmp
                processedBitmaps[pageId] = processed
                val pageNum = scanPages.size + 1
                scanPages.add(
                    ScanPage(
                        id = pageId,
                        title = "Page $pageNum ($sourceLabel)",
                        originalFilePath = "",
                        processedFilePath = "",
                        width = processed.width,
                        height = processed.height,
                        edits = edits,
                        sourceLabel = sourceLabel
                    )
                )
                lastAddedId = pageId

                if (activeSc?.autoOcr == true) {
                    val ocr = PdfAndOcrService.recognizeText(processed)
                    ocrResults[pageId] = ocr
                }
            }

            // If Detect Edges (Crop Box) is enabled, open the interactive 8-handle Crop Dialog immediately
            if (detectEdgesEnabled && lastAddedId != null) {
                postScanCropPageId = lastAddedId
            }

            logActivity(
                "scan",
                "Captured ${expandedBitmaps.size} page(s) via $sourceLabel",
                "Preset: ${effectivePreset.label}"
            )
            pendingShortcut = null
        }
    }

    // Handle incoming shared PDFs/images from external Android apps (WhatsApp, Gmail, Files, Gallery)
    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            val decoded = mutableListOf<Bitmap>()
            for (uri in sharedUris) {
                decoded.addAll(PdfAndOcrService.decodeUriToBitmaps(context, uri))
            }
            if (decoded.isNotEmpty()) {
                val firstMime = context.contentResolver.getType(sharedUris.first())?.lowercase() ?: ""
                if (firstMime.contains("pdf") || decoded.size > 1) {
                    printDocTitle = "Shared_Document"
                    printDocBitmaps = decoded
                    activeTab = MobileTab.PRINT_DOCS
                } else {
                    printPhotoBitmaps = decoded
                    activeTab = MobileTab.PRINT_PHOTOS
                }
                logActivity("print", "Opened ${decoded.size} page(s) from Android Share Sheet", "Ready to print")
            }
            onConsumedSharedUris()
        }
    }

    // 1. Google ML Kit Document Scanner Launcher (Phone Camera with live edge detection & perspective warp)
    val mlKitScannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pages = scanResult?.pages.orEmpty()
            if (pages.isNotEmpty()) {
                scope.launch {
                    val bitmaps = withContext(Dispatchers.IO) {
                        pages.mapNotNull { page ->
                            context.contentResolver.openInputStream(page.imageUri)?.use {
                                BitmapFactory.decodeStream(it)
                            }
                        }
                    }
                    ingestCapturedBitmaps(bitmaps, "Phone Camera")
                }
            }
        }
    }

    // 2. Fallback Standard Camera Capture Launcher (works on all Android devices & emulators without Play Services)
    var fallbackCameraUri by remember { mutableStateOf<Uri?>(null) }
    val fallbackCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = fallbackCameraUri
        if (success && uri != null) {
            scope.launch {
                val bitmaps = PdfAndOcrService.decodeUriToBitmaps(context, uri)
                ingestCapturedBitmaps(bitmaps, "Camera Snap")
            }
        }
    }

    fun launchFallbackCamera() {
        runCatching {
            val scansDir = File(context.cacheDir, "scans").apply { mkdirs() }
            val tempFile = File(scansDir, "snap_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                tempFile
            )
            fallbackCameraUri = uri
            fallbackCameraLauncher.launch(uri)
        }.onFailure { e ->
            Toast.makeText(context, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchPhoneCameraScanner() {
        val hostActivity = activity
        if (hostActivity == null) {
            launchFallbackCamera()
            return
        }
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(24)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(hostActivity)
            .addOnSuccessListener { intentSender ->
                mlKitScannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener {
                // Fallback cleanly to standard camera capture if Play Services module isn't ready yet
                launchFallbackCamera()
            }
    }

    // 3. Import Files Launcher for Scan Tab
    val scanImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val decoded = mutableListOf<Bitmap>()
                for (uri in uris) {
                    decoded.addAll(PdfAndOcrService.decodeUriToBitmaps(context, uri))
                }
                ingestCapturedBitmaps(decoded, "Imported File")
            }
        }
    }

    // 4. Pick Document Launcher for Print Docs Tab
    val printDocPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val pages = PdfAndOcrService.decodeUriToBitmaps(context, uri)
                if (pages.isNotEmpty()) {
                    printDocTitle = uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
                    printDocBitmaps = pages
                }
            }
        }
    }

    // 5. Pick Photos Launcher for Print Photos Tab
    val printPhotosPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val bitmaps = mutableListOf<Bitmap>()
                for (uri in uris) {
                    bitmaps.addAll(PdfAndOcrService.decodeUriToBitmaps(context, uri))
                }
                if (bitmaps.isNotEmpty()) {
                    printPhotoBitmaps = bitmaps
                }
            }
        }
    }

    // Wi-Fi Flatbed eSCL Scan trigger
    fun triggerWifiFlatbedScan() {
        if (printerIp.isBlank()) {
            Toast.makeText(context, "Enter your printer Wi-Fi IP first (e.g. 192.168.0.58)", Toast.LENGTH_LONG).show()
            return
        }
        activeScanJob?.cancel()
        activeScanJob = scope.launch {
            isScanning = true
            scanStatusText = "Starting scan on $printerIp..."
            val result = NetworkPrinterService.scanViaEscl(
                context = context,
                ipOrBaseUrl = printerIp,
                dpi = wifiDpi,
                colorMode = wifiColorMode,
                pageSize = wifiPageSize,
                onProgress = { msg ->
                    scanStatusText = msg
                }
            )
            isScanning = false
            scanStatusText = ""
            result.onSuccess { bitmap ->
                ingestCapturedBitmaps(listOf(bitmap), "Wi-Fi Flatbed ($wifiDpi DPI • $wifiPageSize)")
                Toast.makeText(context, "Flatbed scan complete!", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(
                    context,
                    "Wi-Fi Flatbed Scan failed (${printerIp}): ${err.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    MaterialTheme(colorScheme = if (darkMode) AlPrintDarkScheme else AlPrintLightScheme) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Print,
                                    contentDescription = "AlPrint",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "AlPrint",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "by Almoukhtar Amiouni",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        // Wi-Fi Printer Status Pill
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (printerStatus?.reachable == true) Color(0xFF10B981)
                                            else Color(0xFFF59E0B)
                                        )
                                )
                                Text(
                                    text = printerIp.ifBlank { "Wi-Fi" },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        // Persistent Dark/Light Theme Button
                        IconButton(
                            onClick = {
                                val next = !darkMode
                                darkMode = next
                                storage.setDarkMode(next)
                            }
                        ) {
                            Icon(
                                imageVector = if (darkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "Toggle Theme"
                            )
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = activeTab == MobileTab.HOME,
                        onClick = { activeTab = MobileTab.HOME },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("Home") }
                    )
                    NavigationBarItem(
                        selected = activeTab == MobileTab.SCAN,
                        onClick = { activeTab = MobileTab.SCAN },
                        icon = { Icon(Icons.Default.Scanner, contentDescription = null) },
                        label = { Text("Scan") }
                    )
                    NavigationBarItem(
                        selected = activeTab == MobileTab.PRINT_DOCS,
                        onClick = { activeTab = MobileTab.PRINT_DOCS },
                        icon = { Icon(Icons.Default.Description, contentDescription = null) },
                        label = { Text("Docs") }
                    )
                    NavigationBarItem(
                        selected = activeTab == MobileTab.PRINT_PHOTOS,
                        onClick = { activeTab = MobileTab.PRINT_PHOTOS },
                        icon = { Icon(Icons.Default.Image, contentDescription = null) },
                        label = { Text("Photos") }
                    )
                    NavigationBarItem(
                        selected = activeTab == MobileTab.SHORTCUTS,
                        onClick = { activeTab = MobileTab.SHORTCUTS },
                        icon = { Icon(Icons.Default.Bolt, contentDescription = null) },
                        label = { Text("Shortcuts") }
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (activeTab) {
                    MobileTab.HOME -> {
                        HomeScreen(
                            printerIp = printerIp,
                            printerStatus = printerStatus,
                            discoveredDevices = discoveredDevices,
                            isProbingNetwork = isProbingNetwork,
                            activities = activities,
                            onUpdatePrinterIp = { newIp ->
                                printerIp = newIp.trim()
                                storage.setPrinterIp(newIp.trim())
                            },
                            onRefreshNetwork = { probeNetwork() },
                            onQuickCameraScan = { preset ->
                                inputSource = ScanInputSource.CAMERA
                                activePreset = preset
                                activeTab = MobileTab.SCAN
                                launchPhoneCameraScanner()
                            },
                            onQuickWifiFlatbedScan = {
                                inputSource = ScanInputSource.WIFI_ESCL
                                activePreset = ScanPreset.DOCUMENT
                                activeTab = MobileTab.SCAN
                                triggerWifiFlatbedScan()
                            },
                            onNavigateTab = { tab -> activeTab = tab },
                            onClearActivities = {
                                activities = storage.clearActivities()
                            }
                        )
                    }

                    MobileTab.SCAN -> {
                        ScanScreen(
                            inputSource = inputSource,
                            onSelectInputSource = { inputSource = it },
                            activePreset = activePreset,
                            onSelectPreset = {
                                activePreset = it
                                idCardFrontBitmap = null
                            },
                            detectEdgesEnabled = detectEdgesEnabled,
                            onToggleDetectEdges = { detectEdgesEnabled = it },
                            wifiDpi = wifiDpi,
                            onSelectWifiDpi = { wifiDpi = it },
                            wifiColorMode = wifiColorMode,
                            onSelectWifiColorMode = { wifiColorMode = it },
                            wifiPageSize = wifiPageSize,
                            onSelectWifiPageSize = { wifiPageSize = it },
                            printerIp = printerIp,
                            isScanning = isScanning,
                            scanStatusText = scanStatusText,
                            onCancelScan = {
                                activeScanJob?.cancel()
                                activeScanJob = null
                                isScanning = false
                                scanStatusText = ""
                            },
                            idCardFrontBitmap = idCardFrontBitmap,
                            onCancelIdCardStep = { idCardFrontBitmap = null },
                            pages = scanPages,
                            pageBitmaps = processedBitmaps,
                            originalBitmaps = originalBitmaps,
                            ocrResults = ocrResults,
                            postScanCropPageId = postScanCropPageId,
                            onDismissPostScanCrop = { postScanCropPageId = null },
                            onLaunchPhoneCameraScanner = { launchPhoneCameraScanner() },
                            onLaunchFallbackCamera = { launchFallbackCamera() },
                            onLaunchWifiFlatbedScan = { triggerWifiFlatbedScan() },
                            onLaunchImportFiles = {
                                scanImportLauncher.launch(arrayOf("image/*", "application/pdf"))
                            },
                            onUpdatePageEdits = { pageId, newEdits ->
                                val idx = scanPages.indexOfFirst { it.id == pageId }
                                val orig = originalBitmaps[pageId]
                                if (idx >= 0 && orig != null) {
                                    scope.launch {
                                        val updatedBmp = withContext(Dispatchers.Default) {
                                            ImageProcessor.applyEdits(orig, newEdits)
                                        }
                                        processedBitmaps[pageId] = updatedBmp
                                        scanPages[idx] = scanPages[idx].copy(
                                            edits = newEdits,
                                            width = updatedBmp.width,
                                            height = updatedBmp.height
                                        )
                                    }
                                }
                            },
                            onRotatePage90 = { pageId ->
                                val idx = scanPages.indexOfFirst { it.id == pageId }
                                val orig = originalBitmaps[pageId]
                                if (idx >= 0 && orig != null) {
                                    val oldEdits = scanPages[idx].edits
                                    val newEdits = oldEdits.copy(rotation = (oldEdits.rotation + 90) % 360)
                                    scope.launch {
                                        val updatedBmp = withContext(Dispatchers.Default) {
                                            ImageProcessor.applyEdits(orig, newEdits)
                                        }
                                        processedBitmaps[pageId] = updatedBmp
                                        scanPages[idx] = scanPages[idx].copy(
                                            edits = newEdits,
                                            width = updatedBmp.width,
                                            height = updatedBmp.height
                                        )
                                    }
                                }
                            },
                            onMovePage = { fromIdx, toIdx ->
                                if (fromIdx in scanPages.indices && toIdx in scanPages.indices) {
                                    val item = scanPages.removeAt(fromIdx)
                                    scanPages.add(toIdx, item)
                                }
                            },
                            onDeletePage = { pageId ->
                                scanPages.removeAll { it.id == pageId }
                                originalBitmaps.remove(pageId)
                                processedBitmaps.remove(pageId)
                                ocrResults.remove(pageId)
                            },
                            onClearAllPages = {
                                scanPages.clear()
                                originalBitmaps.clear()
                                processedBitmaps.clear()
                                ocrResults.clear()
                                idCardFrontBitmap = null
                            },
                            onRunOcrForPage = { pageId ->
                                val bmp = processedBitmaps[pageId] ?: return@ScanScreen
                                scope.launch {
                                    val res = PdfAndOcrService.recognizeText(bmp)
                                    ocrResults[pageId] = res
                                    logActivity(
                                        "ocr",
                                        "Offline OCR extracted ${res.words.size} words",
                                        "Suggested name: ${res.suggestedTitle}"
                                    )
                                }
                            },
                            onExportPdf = { shareAfterSave ->
                                scope.launch {
                                    val orderedBitmaps = scanPages.mapNotNull { processedBitmaps[it.id] }
                                    if (orderedBitmaps.isEmpty()) return@launch
                                    val ocrMap = mutableMapOf<Int, OcrResult>()
                                    scanPages.forEachIndexed { index, page ->
                                        ocrResults[page.id]?.let { ocrMap[index] = it }
                                    }
                                    val firstOcrTitle = scanPages.firstNotNullOfOrNull { ocrResults[it.id]?.suggestedTitle }
                                    val baseName = firstOcrTitle
                                        ?: FilenameFormatter.format("AlPrint_{preset}_{date}_{time}", activePreset.label)
                                    val fileName = "$baseName.pdf"

                                    val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
                                    val pdfFile = File(exportDir, fileName)
                                    PdfAndOcrService.buildPdfFile(pdfFile, orderedBitmaps, ocrMap)
                                    saveFileToDownloads(context, pdfFile, fileName, "application/pdf")
                                    logActivity(
                                        "scan",
                                        "Saved PDF: $fileName",
                                        "${orderedBitmaps.size} page(s) • Downloads/AlPrint"
                                    )
                                    Toast.makeText(
                                        context,
                                        "Saved $fileName to Downloads",
                                        Toast.LENGTH_LONG
                                    ).show()

                                    if (shareAfterSave) {
                                        shareFile(context, pdfFile, "application/pdf")
                                    }
                                }
                            },
                            onExportJpeg = { pageId ->
                                val bmp = processedBitmaps[pageId] ?: return@ScanScreen
                                scope.launch {
                                    val fileName = "${FilenameFormatter.format("AlPrint_{date}_{time}", activePreset.label)}.jpg"
                                    val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
                                    val jpgFile = File(exportDir, fileName)
                                    withContext(Dispatchers.IO) {
                                        FileOutputStream(jpgFile).use { out ->
                                            bmp.compress(Bitmap.CompressFormat.JPEG, 94, out)
                                        }
                                    }
                                    saveFileToDownloads(context, jpgFile, fileName, "image/jpeg")
                                    logActivity("scan", "Saved JPEG: $fileName", "Downloads/AlPrint")
                                    Toast.makeText(context, "Saved $fileName to Downloads", Toast.LENGTH_LONG).show()
                                }
                            },
                            onPrintScannedPages = {
                                val orderedBitmaps = scanPages.mapNotNull { processedBitmaps[it.id] }
                                if (orderedBitmaps.isNotEmpty()) {
                                    printDocTitle = "Scanned_Document"
                                    printDocBitmaps = orderedBitmaps
                                    activeTab = MobileTab.PRINT_DOCS
                                }
                            }
                        )
                    }

                    MobileTab.PRINT_DOCS -> {
                        PrintDocsScreen(
                            docTitle = printDocTitle,
                            sourcePages = printDocBitmaps,
                            onPickDocument = {
                                printDocPickerLauncher.launch(arrayOf("application/pdf", "image/*"))
                            },
                            onLoadFromScanSession = {
                                val ordered = scanPages.mapNotNull { processedBitmaps[it.id] }
                                if (ordered.isNotEmpty()) {
                                    printDocTitle = "Scanned_Session"
                                    printDocBitmaps = ordered
                                }
                            },
                            hasScannedSessionPages = scanPages.isNotEmpty(),
                            onLogPrintJob = { title, sub -> logActivity("print", title, sub) }
                        )
                    }

                    MobileTab.PRINT_PHOTOS -> {
                        PrintPhotosScreen(
                            photos = printPhotoBitmaps,
                            onPickPhotos = {
                                printPhotosPickerLauncher.launch("image/*")
                            },
                            onLogPrintJob = { title, sub -> logActivity("print", title, sub) }
                        )
                    }

                    MobileTab.SHORTCUTS -> {
                        ShortcutsScreen(
                            shortcuts = shortcuts,
                            onRunShortcut = { sc ->
                                pendingShortcut = sc
                                inputSource = sc.source
                                activePreset = sc.preset
                                wifiDpi = sc.dpi
                                wifiColorMode = sc.colorMode
                                activeTab = MobileTab.SCAN
                                logActivity("shortcut", "Executed Shortcut: ${sc.name}", sc.subtitle)
                                if (sc.source == ScanInputSource.CAMERA) {
                                    launchPhoneCameraScanner()
                                } else {
                                    triggerWifiFlatbedScan()
                                }
                            },
                            onAddShortcut = { newSc ->
                                val updated = shortcuts + newSc
                                shortcuts = updated
                                storage.saveShortcuts(updated)
                            },
                            onDeleteShortcut = { id ->
                                val updated = shortcuts.filterNot { it.id == id }
                                shortcuts = updated
                                storage.saveShortcuts(updated)
                            }
                        )
                    }
                }
            }
        }
    }
}

private suspend fun saveFileToDownloads(
    context: Context,
    sourceFile: File,
    displayName: String,
    mimeType: String
) = withContext(Dispatchers.IO) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AlPrint")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { output ->
                    sourceFile.inputStream().use { input -> input.copyTo(output) }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "AlPrint"
            ).apply { mkdirs() }
            sourceFile.copyTo(File(dir, displayName), overwrite = true)
        }
    }
}

private fun shareFile(context: Context, file: File, mimeType: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share via AlPrint"))
    }
}
