package com.almoukhtar.alprint.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.almoukhtar.alprint.model.AnnotationStroke
import com.almoukhtar.alprint.model.CropRect
import com.almoukhtar.alprint.model.PageEdits
import com.almoukhtar.alprint.util.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun PageEditorDialog(
    pageTitle: String,
    originalBitmap: Bitmap,
    initialEdits: PageEdits,
    startInCropMode: Boolean = false,
    isPostScanEdgePrompt: Boolean = false,
    onDismiss: () -> Unit,
    onKeepFullPage: (() -> Unit)? = null,
    onSave: (PageEdits) -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        configuration.screenWidthDp > configuration.screenHeightDp

    var activeTab by remember { mutableStateOf(if (startInCropMode) "crop" else "adjust") }
    var rotation by remember { mutableIntStateOf(initialEdits.rotation) }
    var filter by remember { mutableStateOf(initialEdits.filter) }
    var brightness by remember { mutableFloatStateOf(initialEdits.brightness) }
    var contrast by remember { mutableFloatStateOf(initialEdits.contrast) }
    var saturation by remember { mutableFloatStateOf(initialEdits.saturation) }

    var cropEnabled by remember {
        mutableStateOf(startInCropMode || initialEdits.crop != null)
    }
    var cropRect by remember {
        mutableStateOf(
            initialEdits.crop
                ?: initialEdits.suggestedCrop
                ?: CropRect(0.06f, 0.06f, 0.88f, 0.88f)
        )
    }

    val annotations = remember {
        mutableStateListOf<AnnotationStroke>().apply { addAll(initialEdits.annotations) }
    }
    var activeMarkupTool by remember { mutableStateOf("pen") } // "pen", "highlighter", "text"
    var activeColorHex by remember { mutableLongStateOf(0xFFEF4444L) }
    var strokeSize by remember { mutableFloatStateOf(6f) }
    var textStampInput by remember { mutableStateOf("APPROVED") }
    var currentPoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }

    // Render live preview bitmap with current rotation & color adjustments.
    // When in "crop" tab, show the uncropped rotated image so the user can drag the handles/edges over the full frame.
    var previewBitmap by remember { mutableStateOf(originalBitmap) }
    LaunchedEffect(
        rotation,
        filter,
        brightness,
        contrast,
        saturation,
        cropEnabled,
        cropRect,
        activeTab,
        annotations.size
    ) {
        withContext(Dispatchers.Default) {
            val previewEdits = PageEdits(
                rotation = rotation,
                crop = if (activeTab == "crop" || !cropEnabled) null else cropRect,
                filter = filter,
                brightness = brightness,
                contrast = contrast,
                saturation = saturation,
                annotations = if (activeTab == "crop") emptyList() else annotations.toList()
            )
            previewBitmap = ImageProcessor.applyEdits(originalBitmap, previewEdits)
        }
    }

    fun commitSave() {
        onSave(
            PageEdits(
                rotation = rotation,
                crop = if (cropEnabled) cropRect.clamped() else null,
                suggestedCrop = initialEdits.suggestedCrop,
                filter = filter,
                brightness = brightness,
                contrast = contrast,
                saturation = saturation,
                annotations = annotations.toList()
            )
        )
    }

    fun rotateClockwise90() {
        rotation = (rotation + 90) % 360
        // Rotate existing crop box 90° clockwise so it stays aligned with the rotated subject
        if (cropEnabled) {
            val c = cropRect
            cropRect = CropRect(
                x = (1f - (c.y + c.height)).coerceIn(0f, 1f),
                y = c.x.coerceIn(0f, 1f),
                width = c.height,
                height = c.width
            ).clamped()
        }
    }

    fun resetAll() {
        rotation = 0
        filter = "none"
        brightness = 0f
        contrast = 0f
        saturation = 0f
        cropRect = CropRect(0.05f, 0.05f, 0.90f, 0.90f)
        cropEnabled = activeTab == "crop"
        annotations.clear()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (isLandscape) {
                // LANDSCAPE LAYOUT: Full-height Document Canvas on the Left + Compact Side Inspector on the Right
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(6.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0F172A)),
                        contentAlignment = Alignment.Center
                    ) {
                        InteractiveDocumentCanvas(
                            bitmap = previewBitmap,
                            activeTab = activeTab,
                            cropEnabled = cropEnabled,
                            cropRect = cropRect,
                            onCropChange = { cropRect = it.clamped() },
                            activeMarkupTool = activeMarkupTool,
                            activeColorHex = activeColorHex,
                            strokeSize = strokeSize,
                            textStampInput = textStampInput,
                            currentPoints = currentPoints,
                            onUpdateCurrentPoints = { currentPoints = it },
                            onCommitAnnotation = { stroke -> annotations.add(stroke) }
                        )
                    }

                    // Right Inspector Sidebar
                    Surface(
                        tonalElevation = 4.dp,
                        modifier = Modifier
                            .width(280.dp)
                            .fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Top row: Close + Title + Save
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close")
                                }
                                Text(
                                    text = if (isPostScanEdgePrompt) "Verify Crop" else pageTitle,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 4.dp)
                                )
                                Button(
                                    onClick = { commitSave() },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isPostScanEdgePrompt) "Apply" else "Save", maxLines = 1, fontSize = 12.sp)
                                }
                            }

                            if (isPostScanEdgePrompt && onKeepFullPage != null) {
                                OutlinedButton(
                                    onClick = onKeepFullPage,
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text("Keep Full Page (No Crop)", maxLines = 1, fontSize = 12.sp)
                                }
                            }

                            // Mode Switcher Chips
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = activeTab == "crop",
                                    onClick = {
                                        activeTab = "crop"
                                        cropEnabled = true
                                    },
                                    label = { Text("Crop", maxLines = 1, fontSize = 12.sp) }
                                )
                                FilterChip(
                                    selected = activeTab == "adjust",
                                    onClick = { activeTab = "adjust" },
                                    label = { Text("Filters", maxLines = 1, fontSize = 12.sp) }
                                )
                                FilterChip(
                                    selected = activeTab == "markup",
                                    onClick = { activeTab = "markup" },
                                    label = { Text("Markup (${annotations.size})", maxLines = 1, fontSize = 12.sp) }
                                )
                            }

                            EditorToolControls(
                                activeTab = activeTab,
                                rotation = rotation,
                                previewBitmap = previewBitmap,
                                cropEnabled = cropEnabled,
                                cropRect = cropRect,
                                onRotate90 = { rotateClockwise90() },
                                onResetAll = { resetAll() },
                                onAutoDetectCrop = {
                                    val detected = ImageProcessor.detectEdgesRect(previewBitmap)
                                    cropRect = detected ?: CropRect(0.06f, 0.06f, 0.88f, 0.88f)
                                    cropEnabled = true
                                },
                                onSelectFullPageCrop = {
                                    cropRect = CropRect(0f, 0f, 1f, 1f)
                                },
                                filter = filter,
                                onSelectFilter = { filter = it },
                                brightness = brightness,
                                onBrightnessChange = { brightness = it },
                                contrast = contrast,
                                onContrastChange = { contrast = it },
                                saturation = saturation,
                                onSaturationChange = { saturation = it },
                                activeMarkupTool = activeMarkupTool,
                                onSelectMarkupTool = { activeMarkupTool = it },
                                activeColorHex = activeColorHex,
                                onSelectColorHex = { activeColorHex = it },
                                textStampInput = textStampInput,
                                onTextStampChange = { textStampInput = it },
                                hasAnnotations = annotations.isNotEmpty(),
                                onUndoAnnotation = {
                                    if (annotations.isNotEmpty()) annotations.removeAt(annotations.lastIndex)
                                },
                                onClearAnnotations = { annotations.clear() }
                            )
                        }
                    }
                }
            } else {
                // PORTRAIT LAYOUT: Compact Top Header + Mode Tabs + Maximized Center Canvas + Bottom Controls
                Column(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        tonalElevation = 3.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.Default.Close, contentDescription = "Close")
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isPostScanEdgePrompt) "Verify Document Crop" else pageTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Drag corners or border lines to adjust",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isPostScanEdgePrompt && onKeepFullPage != null) {
                                    OutlinedButton(
                                        onClick = onKeepFullPage,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("Full Page", maxLines = 1, fontSize = 12.sp)
                                    }
                                }
                                Button(
                                    onClick = { commitSave() },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isPostScanEdgePrompt) "Apply" else "Save",
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = activeTab == "crop",
                            onClick = {
                                activeTab = "crop"
                                cropEnabled = true
                            },
                            label = { Text("Crop Box", maxLines = 1) },
                            leadingIcon = { Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = activeTab == "adjust",
                            onClick = { activeTab = "adjust" },
                            label = { Text("Filters & Tone", maxLines = 1) },
                            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = activeTab == "markup",
                            onClick = { activeTab = "markup" },
                            label = { Text("Markup (${annotations.size})", maxLines = 1) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0F172A)),
                        contentAlignment = Alignment.Center
                    ) {
                        InteractiveDocumentCanvas(
                            bitmap = previewBitmap,
                            activeTab = activeTab,
                            cropEnabled = cropEnabled,
                            cropRect = cropRect,
                            onCropChange = { cropRect = it.clamped() },
                            activeMarkupTool = activeMarkupTool,
                            activeColorHex = activeColorHex,
                            strokeSize = strokeSize,
                            textStampInput = textStampInput,
                            currentPoints = currentPoints,
                            onUpdateCurrentPoints = { currentPoints = it },
                            onCommitAnnotation = { stroke -> annotations.add(stroke) }
                        )
                    }

                    Surface(
                        tonalElevation = 4.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            EditorToolControls(
                                activeTab = activeTab,
                                rotation = rotation,
                                previewBitmap = previewBitmap,
                                cropEnabled = cropEnabled,
                                cropRect = cropRect,
                                onRotate90 = { rotateClockwise90() },
                                onResetAll = { resetAll() },
                                onAutoDetectCrop = {
                                    val detected = ImageProcessor.detectEdgesRect(previewBitmap)
                                    cropRect = detected ?: CropRect(0.06f, 0.06f, 0.88f, 0.88f)
                                    cropEnabled = true
                                },
                                onSelectFullPageCrop = {
                                    cropRect = CropRect(0f, 0f, 1f, 1f)
                                },
                                filter = filter,
                                onSelectFilter = { filter = it },
                                brightness = brightness,
                                onBrightnessChange = { brightness = it },
                                contrast = contrast,
                                onContrastChange = { contrast = it },
                                saturation = saturation,
                                onSaturationChange = { saturation = it },
                                activeMarkupTool = activeMarkupTool,
                                onSelectMarkupTool = { activeMarkupTool = it },
                                activeColorHex = activeColorHex,
                                onSelectColorHex = { activeColorHex = it },
                                textStampInput = textStampInput,
                                onTextStampChange = { textStampInput = it },
                                hasAnnotations = annotations.isNotEmpty(),
                                onUndoAnnotation = {
                                    if (annotations.isNotEmpty()) annotations.removeAt(annotations.lastIndex)
                                },
                                onClearAnnotations = { annotations.clear() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorToolControls(
    activeTab: String,
    rotation: Int,
    previewBitmap: Bitmap,
    cropEnabled: Boolean,
    cropRect: CropRect,
    onRotate90: () -> Unit,
    onResetAll: () -> Unit,
    onAutoDetectCrop: () -> Unit,
    onSelectFullPageCrop: () -> Unit,
    filter: String,
    onSelectFilter: (String) -> Unit,
    brightness: Float,
    onBrightnessChange: (Float) -> Unit,
    contrast: Float,
    onContrastChange: (Float) -> Unit,
    saturation: Float,
    onSaturationChange: (Float) -> Unit,
    activeMarkupTool: String,
    onSelectMarkupTool: (String) -> Unit,
    activeColorHex: Long,
    onSelectColorHex: (Long) -> Unit,
    textStampInput: String,
    onTextStampChange: (String) -> Unit,
    hasAnnotations: Boolean,
    onUndoAnnotation: () -> Unit,
    onClearAnnotations: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onRotate90,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(Icons.Default.RotateRight, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Rotate (${rotation}°)", maxLines = 1, fontSize = 12.sp)
        }

        if (activeTab == "crop") {
            val pxW = (cropRect.width * previewBitmap.width).roundToInt()
            val pxH = (cropRect.height * previewBitmap.height).roundToInt()
            Text(
                text = if (cropEnabled) "${pxW}×${pxH}" else "Full",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        TextButton(
            onClick = onResetAll,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(3.dp))
            Text("Reset", maxLines = 1, fontSize = 12.sp)
        }
    }

    when (activeTab) {
        "crop" -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onAutoDetectCrop,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text("Auto Edges", maxLines = 1, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onSelectFullPageCrop,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text("Full Page", maxLines = 1, fontSize = 12.sp)
                }
            }
        }

        "adjust" -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val filters = listOf(
                    "none" to "Original",
                    "auto_enhance" to "Auto-Enhance",
                    "grayscale" to "Grayscale",
                    "bw_document" to "B&W Crisp"
                )
                for ((key, label) in filters) {
                    FilterChip(
                        selected = filter == key,
                        onClick = { onSelectFilter(key) },
                        label = { Text(label, maxLines = 1, fontSize = 12.sp) }
                    )
                }
            }

            CompactSliderRow(
                label = "Brightness",
                value = brightness,
                onValueChange = onBrightnessChange
            )
            CompactSliderRow(
                label = "Contrast",
                value = contrast,
                onValueChange = onContrastChange
            )
            CompactSliderRow(
                label = "Saturation",
                value = saturation,
                onValueChange = onSaturationChange
            )
        }

        "markup" -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = activeMarkupTool == "pen",
                    onClick = { onSelectMarkupTool("pen") },
                    label = { Text("Pen", maxLines = 1, fontSize = 12.sp) }
                )
                FilterChip(
                    selected = activeMarkupTool == "highlighter",
                    onClick = { onSelectMarkupTool("highlighter") },
                    label = { Text("Highlight", maxLines = 1, fontSize = 12.sp) }
                )
                FilterChip(
                    selected = activeMarkupTool == "text",
                    onClick = { onSelectMarkupTool("text") },
                    label = { Text("Text", maxLines = 1, fontSize = 12.sp) }
                )
                TextButton(
                    onClick = onUndoAnnotation,
                    enabled = hasAnnotations
                ) {
                    Text("Undo", maxLines = 1, fontSize = 12.sp)
                }
                TextButton(
                    onClick = onClearAnnotations,
                    enabled = hasAnnotations
                ) {
                    Text("Clear", maxLines = 1, fontSize = 12.sp)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val colors = listOf(
                    0xFFEF4444L,
                    0xFF3B82F6L,
                    0xFF10B981L,
                    0xFFF59E0BL,
                    0xFF111827L
                )
                Text("Color:", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                for (hex in colors) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(hex))
                            .border(
                                width = if (activeColorHex == hex) 3.dp else 1.dp,
                                color = if (activeColorHex == hex) MaterialTheme.colorScheme.primary else Color.White,
                                shape = CircleShape
                            )
                            .clickable { onSelectColorHex(hex) }
                    )
                }
            }

            if (activeMarkupTool == "text") {
                OutlinedTextField(
                    value = textStampInput,
                    onValueChange = onTextStampChange,
                    label = { Text("Tap on document to stamp", maxLines = 1) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun CompactSliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label (${value.roundToInt()})",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.width(96.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = -100f..100f,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = { onValueChange(0f) },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
        ) {
            Text("0", fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun InteractiveDocumentCanvas(
    bitmap: Bitmap,
    activeTab: String,
    cropEnabled: Boolean,
    cropRect: CropRect,
    onCropChange: (CropRect) -> Unit,
    activeMarkupTool: String,
    activeColorHex: Long,
    strokeSize: Float,
    textStampInput: String,
    currentPoints: List<Pair<Float, Float>>,
    onUpdateCurrentPoints: (List<Pair<Float, Float>>) -> Unit,
    onCommitAnnotation: (AnnotationStroke) -> Unit
) {
    var activeHandle by remember { mutableStateOf<String?>(null) }
    var imageBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }

    val latestCropRect by rememberUpdatedState(cropRect)
    val latestOnCropChange by rememberUpdatedState(onCropChange)
    val latestPoints by rememberUpdatedState(currentPoints)
    val latestOnUpdatePoints by rememberUpdatedState(onUpdateCurrentPoints)
    val latestOnCommitAnnotation by rememberUpdatedState(onCommitAnnotation)
    val latestMarkupTool by rememberUpdatedState(activeMarkupTool)
    val latestColorHex by rememberUpdatedState(activeColorHex)
    val latestStrokeSize by rememberUpdatedState(strokeSize)
    val latestTextStamp by rememberUpdatedState(textStampInput)

    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(activeTab, cropEnabled) {
                val cornerTouchRadiusPx = 36.dp.toPx()
                val edgeTouchRadiusPx = 28.dp.toPx()

                detectDragGestures(
                    onDragStart = { pos ->
                        val rect = imageBounds
                        if (rect.width <= 1f || rect.height <= 1f) return@detectDragGestures

                        if (activeTab == "crop" && cropEnabled) {
                            activeHandle = hitTestCropHandlePx(
                                touchX = pos.x,
                                touchY = pos.y,
                                imgRect = rect,
                                crop = latestCropRect,
                                cornerRadiusPx = cornerTouchRadiusPx,
                                edgeRadiusPx = edgeTouchRadiusPx
                            )
                        } else if (activeTab == "markup") {
                            val nx = ((pos.x - rect.left) / rect.width).coerceIn(0f, 1f)
                            val ny = ((pos.y - rect.top) / rect.height).coerceIn(0f, 1f)
                            if (latestMarkupTool == "text") {
                                latestOnCommitAnnotation(
                                    AnnotationStroke(
                                        id = "txt_${System.currentTimeMillis()}",
                                        tool = "text",
                                        colorHex = latestColorHex,
                                        size = 24f,
                                        points = listOf(nx to ny),
                                        text = latestTextStamp.ifBlank { "NOTE" }
                                    )
                                )
                            } else {
                                latestOnUpdatePoints(listOf(nx to ny))
                            }
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val rect = imageBounds
                        if (rect.width <= 1f || rect.height <= 1f) return@detectDragGestures

                        if (activeTab == "crop" && cropEnabled) {
                            val dx = dragAmount.x / rect.width
                            val dy = dragAmount.y / rect.height
                            val updated = applyHandleDrag(
                                c = latestCropRect,
                                handle = activeHandle ?: "move",
                                dx = dx,
                                dy = dy
                            )
                            latestOnCropChange(updated)
                        } else if (activeTab == "markup" && latestMarkupTool != "text") {
                            val nx = ((change.position.x - rect.left) / rect.width).coerceIn(0f, 1f)
                            val ny = ((change.position.y - rect.top) / rect.height).coerceIn(0f, 1f)
                            latestOnUpdatePoints(latestPoints + (nx to ny))
                        }
                    },
                    onDragEnd = {
                        activeHandle = null
                        if (activeTab == "markup" && latestPoints.size >= 2) {
                            latestOnCommitAnnotation(
                                AnnotationStroke(
                                    id = "str_${System.currentTimeMillis()}",
                                    tool = latestMarkupTool,
                                    colorHex = latestColorHex,
                                    size = if (latestMarkupTool == "highlighter") 18f else latestStrokeSize,
                                    points = latestPoints
                                )
                            )
                        }
                        latestOnUpdatePoints(emptyList())
                    },
                    onDragCancel = {
                        activeHandle = null
                        latestOnUpdatePoints(emptyList())
                    }
                )
            }
    ) {
        val canvasW = size.width
        val canvasH = size.height
        val bmpW = bitmap.width.toFloat().coerceAtLeast(1f)
        val bmpH = bitmap.height.toFloat().coerceAtLeast(1f)

        val pad = 10.dp.toPx()
        val availW = (canvasW - pad * 2f).coerceAtLeast(1f)
        val availH = (canvasH - pad * 2f).coerceAtLeast(1f)

        val scale = min(availW / bmpW, availH / bmpH)
        val drawW = bmpW * scale
        val drawH = bmpH * scale
        val left = (canvasW - drawW) / 2f
        val top = (canvasH - drawH) / 2f
        imageBounds = androidx.compose.ui.geometry.Rect(left, top, left + drawW, top + drawH)

        drawImage(
            image = imageBitmap,
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt())
        )

        if (activeTab == "markup" && currentPoints.size >= 2) {
            val path = Path()
            val first = currentPoints.first()
            path.moveTo(left + first.first * drawW, top + first.second * drawH)
            for (i in 1 until currentPoints.size) {
                val pt = currentPoints[i]
                path.lineTo(left + pt.first * drawW, top + pt.second * drawH)
            }
            drawPath(
                path = path,
                color = Color(activeColorHex).copy(alpha = if (activeMarkupTool == "highlighter") 0.45f else 1f),
                style = Stroke(
                    width = if (activeMarkupTool == "highlighter") 26f else 8f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        if (activeTab == "crop" && cropEnabled) {
            val cl = left + cropRect.x * drawW
            val ct = top + cropRect.y * drawH
            val cw = cropRect.width * drawW
            val ch = cropRect.height * drawH

            val dimColor = Color.Black.copy(alpha = 0.58f)
            drawRect(dimColor, Offset(left, top), Size(drawW, (ct - top).coerceAtLeast(0f)))
            drawRect(dimColor, Offset(left, ct + ch), Size(drawW, (top + drawH - (ct + ch)).coerceAtLeast(0f)))
            drawRect(dimColor, Offset(left, ct), Size((cl - left).coerceAtLeast(0f), ch))
            drawRect(dimColor, Offset(cl + cw, ct), Size((left + drawW - (cl + cw)).coerceAtLeast(0f), ch))

            val accent = Color(0xFF38BDF8)
            drawRect(
                color = accent,
                topLeft = Offset(cl, ct),
                size = Size(cw, ch),
                style = Stroke(width = 2.dp.toPx())
            )

            val gridColor = Color.White.copy(alpha = 0.45f)
            drawLine(gridColor, Offset(cl + cw / 3f, ct), Offset(cl + cw / 3f, ct + ch), 1.dp.toPx())
            drawLine(gridColor, Offset(cl + (cw * 2f) / 3f, ct), Offset(cl + (cw * 2f) / 3f, ct + ch), 1.dp.toPx())
            drawLine(gridColor, Offset(cl, ct + ch / 3f), Offset(cl + cw, ct + ch / 3f), 1.dp.toPx())
            drawLine(gridColor, Offset(cl, ct + (ch * 2f) / 3f), Offset(cl + cw, ct + (ch * 2f) / 3f), 1.dp.toPx())

            val handlePoints = listOf(
                "nw" to Offset(cl, ct),
                "n" to Offset(cl + cw / 2f, ct),
                "ne" to Offset(cl + cw, ct),
                "e" to Offset(cl + cw, ct + ch / 2f),
                "se" to Offset(cl + cw, ct + ch),
                "s" to Offset(cl + cw / 2f, ct + ch),
                "sw" to Offset(cl, ct + ch),
                "w" to Offset(cl, ct + ch / 2f)
            )
            for ((id, pt) in handlePoints) {
                val isActive = activeHandle == id
                val radius = if (isActive) 8.5.dp.toPx() else 5.5.dp.toPx()
                drawCircle(Color.White, radius = radius + 1.5.dp.toPx(), center = pt)
                drawCircle(if (isActive) Color(0xFF0284C7) else accent, radius = radius, center = pt)
            }
        }
    }
}

private fun hitTestCropHandlePx(
    touchX: Float,
    touchY: Float,
    imgRect: androidx.compose.ui.geometry.Rect,
    crop: CropRect,
    cornerRadiusPx: Float,
    edgeRadiusPx: Float
): String {
    val cl = imgRect.left + crop.x * imgRect.width
    val ct = imgRect.top + crop.y * imgRect.height
    val cr = cl + crop.width * imgRect.width
    val cb = ct + crop.height * imgRect.height
    val midX = (cl + cr) / 2f
    val midY = (ct + cb) / 2f

    val points = listOf(
        "nw" to Offset(cl, ct),
        "ne" to Offset(cr, ct),
        "se" to Offset(cr, cb),
        "sw" to Offset(cl, cb),
        "n" to Offset(midX, ct),
        "s" to Offset(midX, cb),
        "w" to Offset(cl, midY),
        "e" to Offset(cr, midY)
    )
    var bestHandle: String? = null
    var bestDist = cornerRadiusPx
    for ((name, pt) in points) {
        val d = hypot(touchX - pt.x, touchY - pt.y)
        if (d <= bestDist) {
            bestDist = d
            bestHandle = name
        }
    }
    if (bestHandle != null) return bestHandle

    val inHorizSpan = touchX in (cl - edgeRadiusPx)..(cr + edgeRadiusPx)
    val inVertSpan = touchY in (ct - edgeRadiusPx)..(cb + edgeRadiusPx)

    val distTop = abs(touchY - ct)
    val distBottom = abs(touchY - cb)
    val distLeft = abs(touchX - cl)
    val distRight = abs(touchX - cr)

    val edgeCandidates = mutableListOf<Pair<String, Float>>()
    if (inHorizSpan && distTop <= edgeRadiusPx) edgeCandidates.add("n" to distTop)
    if (inHorizSpan && distBottom <= edgeRadiusPx) edgeCandidates.add("s" to distBottom)
    if (inVertSpan && distLeft <= edgeRadiusPx) edgeCandidates.add("w" to distLeft)
    if (inVertSpan && distRight <= edgeRadiusPx) edgeCandidates.add("e" to distRight)

    if (edgeCandidates.isNotEmpty()) {
        return edgeCandidates.minBy { it.second }.first
    }

    return "move"
}

private fun applyHandleDrag(c: CropRect, handle: String, dx: Float, dy: Float): CropRect {
    var left = c.x
    var top = c.y
    var right = c.x + c.width
    var bottom = c.y + c.height
    val minSize = 0.08f

    when (handle) {
        "nw" -> {
            left = (left + dx).coerceIn(0f, right - minSize)
            top = (top + dy).coerceIn(0f, bottom - minSize)
        }
        "n" -> top = (top + dy).coerceIn(0f, bottom - minSize)
        "ne" -> {
            right = (right + dx).coerceIn(left + minSize, 1f)
            top = (top + dy).coerceIn(0f, bottom - minSize)
        }
        "e" -> right = (right + dx).coerceIn(left + minSize, 1f)
        "se" -> {
            right = (right + dx).coerceIn(left + minSize, 1f)
            bottom = (bottom + dy).coerceIn(top + minSize, 1f)
        }
        "s" -> bottom = (bottom + dy).coerceIn(top + minSize, 1f)
        "sw" -> {
            left = (left + dx).coerceIn(0f, right - minSize)
            bottom = (bottom + dy).coerceIn(top + minSize, 1f)
        }
        "w" -> left = (left + dx).coerceIn(0f, right - minSize)
        else -> {
            val w = c.width
            val h = c.height
            val newX = (left + dx).coerceIn(0f, 1f - w)
            val newY = (top + dy).coerceIn(0f, 1f - h)
            return CropRect(newX, newY, w, h)
        }
    }
    return CropRect(left, top, right - left, bottom - top).clamped()
}
