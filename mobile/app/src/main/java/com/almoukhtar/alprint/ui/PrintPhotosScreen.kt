package com.almoukhtar.alprint.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.almoukhtar.alprint.model.DuplexMode
import com.almoukhtar.alprint.util.ImageProcessor
import com.almoukhtar.alprint.util.PdfAndOcrService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PrintPhotosScreen(
    photos: List<Bitmap>,
    onPickPhotos: () -> Unit,
    onLogPrintJob: (String, String) -> Unit
) {
    val context = LocalContext.current
    var selectedPhotoSize by remember { mutableStateOf("4x6\"") }
    var fillMode by remember { mutableStateOf("fill") } // "fill" (borderless crop) or "fit"
    var landscape by remember { mutableStateOf(false) }
    var extraRotation by remember { mutableIntStateOf(0) }
    var currentPhotoIndex by remember(photos.size) { mutableIntStateOf(0) }

    val photoSizes = listOf("4x6\"", "5x7\"", "8x10\"", "A4", "Letter")

    // Compute exact paper sheet aspect ratio for the selected paper size & orientation
    val (sheetW, sheetH) = remember(selectedPhotoSize, landscape) {
        PdfAndOcrService.getPaperDimensions(selectedPhotoSize, landscape)
    }
    val sheetAspectRatio = remember(sheetW, sheetH) {
        sheetW.toFloat() / sheetH.toFloat()
    }
    val dimensionsLabel = remember(selectedPhotoSize, landscape) {
        PdfAndOcrService.getPaperDimensionsLabel(selectedPhotoSize, landscape)
    }

    val activeRawPhoto = photos.getOrNull(currentPhotoIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0)))
    var composedSheetPreview by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(activeRawPhoto, selectedPhotoSize, fillMode, landscape, extraRotation) {
        if (activeRawPhoto == null) {
            composedSheetPreview = null
        } else {
            withContext(Dispatchers.Default) {
                val rotated = if (extraRotation == 0) {
                    activeRawPhoto
                } else {
                    ImageProcessor.rotateBitmap(activeRawPhoto, extraRotation)
                }
                composedSheetPreview = PdfAndOcrService.composePhotoSheet(
                    photo = rotated,
                    paperSize = selectedPhotoSize,
                    fillMode = fillMode,
                    landscape = landscape
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Photo Printing Studio",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Select one or more photos from your gallery for borderless 4×6\", 5×7\", 8×10\", or full-sheet A4/Letter printing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onPickPhotos,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Select Photos (${photos.size})", maxLines = 1)
                    }
                    if (photos.isNotEmpty()) {
                        OutlinedButton(onClick = { extraRotation = (extraRotation + 90) % 360 }) {
                            Icon(Icons.Default.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Rotate Photo", maxLines = 1)
                        }
                    }
                }
            }
        }

        if (photos.isNotEmpty()) {
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
                                text = "Live Paper & Framing Preview",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "$dimensionsLabel • ${if (landscape) "Landscape" else "Portrait"} • ${if (fillMode == "fill") "Borderless Fill" else "Fit with Margins"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (photos.size > 1) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { if (currentPhotoIndex > 0) currentPhotoIndex-- },
                                    enabled = currentPhotoIndex > 0
                                ) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Previous Photo")
                                }
                                Text(
                                    text = "${currentPhotoIndex + 1}/${photos.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(
                                    onClick = { if (currentPhotoIndex < photos.lastIndex) currentPhotoIndex++ },
                                    enabled = currentPhotoIndex < photos.lastIndex
                                ) {
                                    Icon(Icons.Default.ArrowForward, contentDescription = "Next Photo")
                                }
                            }
                        }
                    }

                    // Dark Printbed Viewport showing the true proportional White Paper Sheet inside
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(330.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0F172A))
                            .padding(18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // White physical paper sheet sized to the exact aspect ratio of 4x6, 5x7, 8x10, A4, or Letter
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
                            val sheetBmp = composedSheetPreview
                            if (sheetBmp != null) {
                                Image(
                                    bitmap = sheetBmp.asImageBitmap(),
                                    contentDescription = "Composed Photo Sheet Preview",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        // Corner Badge showing Paper Size & Orientation
                        Surface(
                            color = Color(0xCC0F172A),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                        ) {
                            Text(
                                text = "$selectedPhotoSize • ${if (landscape) "Landscape" else "Portrait"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Paper Size & Framing",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (sz in photoSizes) {
                        FilterChip(
                            selected = selectedPhotoSize == sz,
                            onClick = { selectedPhotoSize = sz },
                            label = { Text(sz) }
                        )
                    }
                }

                Text(
                    text = "Framing & Orientation",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = fillMode == "fill",
                        onClick = { fillMode = "fill" },
                        label = { Text("Borderless Fill") }
                    )
                    FilterChip(
                        selected = fillMode == "fit",
                        onClick = { fillMode = "fit" },
                        label = { Text("Fit Entire Photo") }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !landscape,
                        onClick = { landscape = false },
                        label = { Text("Portrait (Vertical)") }
                    )
                    FilterChip(
                        selected = landscape,
                        onClick = { landscape = true },
                        label = { Text("Landscape (Horizontal)") }
                    )
                }

                Button(
                    onClick = {
                        val sheets = photos.map { raw ->
                            val rotated = if (extraRotation == 0) raw else ImageProcessor.rotateBitmap(raw, extraRotation)
                            PdfAndOcrService.composePhotoSheet(
                                photo = rotated,
                                paperSize = selectedPhotoSize,
                                fillMode = fillMode,
                                landscape = landscape
                            )
                        }
                        PdfAndOcrService.printBitmapsWithAndroidSpooler(
                            context = context,
                            jobName = "AlPrint_Photo_${selectedPhotoSize.replace("\"", "in")}",
                            sheets = sheets,
                            duplexMode = DuplexMode.ONE_SIDED,
                            grayscale = false,
                            landscape = landscape,
                            paperSize = selectedPhotoSize
                        )
                        onLogPrintJob(
                            "Print Photos ($selectedPhotoSize ${if (landscape) "Landscape" else "Portrait"})",
                            "${photos.size} photo(s) • ${if (fillMode == "fill") "Borderless Fill" else "Fit"}"
                        )
                    },
                    enabled = photos.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Print ${photos.size} Photo${if (photos.size == 1) "" else "s"} ($selectedPhotoSize)")
                }
            }
        }
    }
}
