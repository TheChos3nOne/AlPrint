package com.almoukhtar.alprint.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Scanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.almoukhtar.alprint.model.FilenameFormatter
import com.almoukhtar.alprint.model.ScanInputSource
import com.almoukhtar.alprint.model.ScanPreset
import com.almoukhtar.alprint.model.ShortcutConfig

@Composable
fun ShortcutsScreen(
    shortcuts: List<ShortcutConfig>,
    onRunShortcut: (ShortcutConfig) -> Unit,
    onAddShortcut: (ShortcutConfig) -> Unit,
    onDeleteShortcut: (String) -> Unit
) {
    var showCreateModal by remember { mutableStateOf(false) }

    if (showCreateModal) {
        CreateShortcutDialog(
            onDismiss = { showCreateModal = false },
            onCreate = { newSc ->
                onAddShortcut(newSc)
                showCreateModal = false
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "1-Tap Smart Shortcuts",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Automate Camera or Wi-Fi Flatbed scan, filter, offline OCR & PDF/Print in 1 tap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = { showCreateModal = true }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New")
            }
        }

        for (sc in shortcuts) {
            Card(
                shape = RoundedCornerShape(16.dp),
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (sc.source == ScanInputSource.CAMERA) Icons.Default.CameraAlt else Icons.Default.Scanner,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = sc.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = sc.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IconButton(onClick = { onDeleteShortcut(sc.id) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete Shortcut",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "File: ${FilenameFormatter.format(sc.filenameTemplate, sc.preset.label)}.${if (sc.outputFormat == "JPEG") "jpg" else "pdf"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = { onRunShortcut(sc) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Run Now")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateShortcutDialog(
    onDismiss: () -> Unit,
    onCreate: (ShortcutConfig) -> Unit
) {
    var name by remember { mutableStateOf("Tax Receipt to PDF") }
    var source by remember { mutableStateOf(ScanInputSource.CAMERA) }
    var preset by remember { mutableStateOf(ScanPreset.DOCUMENT) }
    var filter by remember { mutableStateOf("auto_enhance") }
    var outputFormat by remember { mutableStateOf("PDF") }
    var template by remember { mutableStateOf("Scan_{preset}_{date}") }
    var autoOcr by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null)
                Text("Create 1-Tap Shortcut", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Shortcut Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Capture Source:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = source == ScanInputSource.CAMERA,
                        onClick = { source = ScanInputSource.CAMERA },
                        label = { Text("Phone Camera") }
                    )
                    FilterChip(
                        selected = source == ScanInputSource.WIFI_ESCL,
                        onClick = { source = ScanInputSource.WIFI_ESCL },
                        label = { Text("Wi-Fi Flatbed") }
                    )
                }

                Text("Preset Mode:", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (p in listOf(ScanPreset.DOCUMENT, ScanPreset.ID_CARD, ScanPreset.PHOTO, ScanPreset.BOOK)) {
                        FilterChip(
                            selected = preset == p,
                            onClick = { preset = p },
                            label = { Text(p.label) }
                        )
                    }
                }

                Text("Output Action:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (out in listOf("PDF", "JPEG", "PRINT")) {
                        FilterChip(
                            selected = outputFormat == out,
                            onClick = { outputFormat = out },
                            label = { Text(out) }
                        )
                    }
                }

                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it },
                    label = { Text("Filename Template ({date}, {time}, {preset})") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Run Offline OCR Automatically", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = autoOcr, onCheckedChange = { autoOcr = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanName = name.trim().ifEmpty { "Custom Shortcut" }
                    onCreate(
                        ShortcutConfig(
                            id = "sc_${System.currentTimeMillis()}",
                            name = cleanName,
                            subtitle = "${source.label} • ${preset.label} • $outputFormat",
                            source = source,
                            preset = preset,
                            dpi = 300,
                            colorMode = "Color",
                            filter = filter,
                            outputFormat = outputFormat,
                            filenameTemplate = template.ifBlank { "Scan_{date}_{time}" },
                            autoOcr = autoOcr
                        )
                    )
                }
            ) {
                Text("Save Shortcut")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
