package com.almoukhtar.alprint.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Scanner
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.almoukhtar.alprint.model.ActivityEntry
import com.almoukhtar.alprint.model.DiscoveredDevice
import com.almoukhtar.alprint.model.MobileTab
import com.almoukhtar.alprint.model.PrinterNetworkStatus
import com.almoukhtar.alprint.model.ScanInputSource
import com.almoukhtar.alprint.model.ScanPreset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    printerIp: String,
    printerStatus: PrinterNetworkStatus?,
    discoveredDevices: List<DiscoveredDevice>,
    isProbingNetwork: Boolean,
    activities: List<ActivityEntry>,
    onUpdatePrinterIp: (String) -> Unit,
    onRefreshNetwork: () -> Unit,
    onQuickCameraScan: (ScanPreset) -> Unit,
    onQuickWifiFlatbedScan: () -> Unit,
    onNavigateTab: (MobileTab) -> Unit,
    onClearActivities: () -> Unit
) {
    val context = LocalContext.current
    var ipInput by remember(printerIp) { mutableStateOf(printerIp) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Active Wi-Fi Printer & Flatbed Scanner Hub Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Print,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Column {
                            Text(
                                text = printerStatus?.modelName ?: "HP Smart Tank 580-590 series",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
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
                                    text = printerStatus?.stateLabel ?: "Probing Wi-Fi printer...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (isProbingNetwork) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }

                // Discovered LAN Devices Chips
                if (discoveredDevices.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Wifi,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        for (dev in discoveredDevices.take(3)) {
                            FilterChip(
                                selected = dev.ip == printerIp,
                                onClick = {
                                    ipInput = dev.ip
                                    onUpdatePrinterIp(dev.ip)
                                },
                                label = {
                                    Text(
                                        text = "${dev.name} (${dev.ip})",
                                        maxLines = 1
                                    )
                                }
                            )
                        }
                    }
                }

                // IP Input + Connect / Discover Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        label = { Text("Printer Wi-Fi IP", maxLines = 1) },
                        placeholder = { Text("192.168.0.58") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            onUpdatePrinterIp(ipInput)
                            onRefreshNetwork()
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Connect", maxLines = 1)
                    }
                }

                // SNMP Supply Levels (when reported over UDP 161 with non-zero levels)
                val supplies = printerStatus?.supplies.orEmpty()
                if (supplies.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Live Ink Tank Telemetry (SNMP)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        for (supply in supplies) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = supply.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.width(90.dp)
                                )
                                LinearProgressIndicator(
                                    progress = { supply.levelPercent / 100f },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = Color(supply.colorHex)
                                )
                                Text(
                                    text = "${supply.levelPercent}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.width(38.dp)
                                )
                            }
                        }
                    }
                }

                // Embedded Web Server (EWS) Dashboard Shortcut & Offline Guarantee Footer
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (printerIp.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://${printerIp.trim()}"))
                                    context.startActivity(intent)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Printer Web Dashboard (${printerIp.trim()})", maxLines = 1)
                        }
                    }
                    Text(
                        text = "100% Offline • Zero Telemetry • No Account Required",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Quick Actions Section
        Text(
            text = "Quick Actions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickActionTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.CameraAlt,
                    title = "Phone Camera Scan",
                    subtitle = "Live edge tracking & auto-perspective crop",
                    accentColor = Color(0xFF2563EB),
                    onClick = { onQuickCameraScan(ScanPreset.DOCUMENT) }
                )
                QuickActionTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Scanner,
                    title = "Wi-Fi Flatbed Scan",
                    subtitle = "Scan directly from HP Smart Tank glass (eSCL)",
                    accentColor = Color(0xFF0D9488),
                    onClick = onQuickWifiFlatbedScan
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickActionTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Badge,
                    title = "ID Card (2-Step)",
                    subtitle = "Front + Back stitched onto 1 printable sheet",
                    accentColor = Color(0xFF7C3AED),
                    onClick = { onQuickCameraScan(ScanPreset.ID_CARD) }
                )
                QuickActionTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Description,
                    title = "Print PDF / Docs",
                    subtitle = "N-up sheets, page ranges & 2×2 Duplex grid",
                    accentColor = Color(0xFFEA580C),
                    onClick = { onNavigateTab(MobileTab.PRINT_DOCS) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickActionTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Image,
                    title = "Print Photos",
                    subtitle = "4×6\", 5×7\", A4 borderless fill or fit",
                    accentColor = Color(0xFFDB2777),
                    onClick = { onNavigateTab(MobileTab.PRINT_PHOTOS) }
                )
                QuickActionTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Bolt,
                    title = "1-Tap Shortcuts",
                    subtitle = "Run saved Camera or Flatbed workflows",
                    accentColor = Color(0xFFD97706),
                    onClick = { onNavigateTab(MobileTab.SHORTCUTS) }
                )
            }
        }

        // Recent Local Activity Log (with Clear button)
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Local Activity",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (activities.isNotEmpty()) {
                        TextButton(onClick = onClearActivities) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear")
                        }
                    }
                }

                if (activities.isEmpty()) {
                    Text(
                        text = "No recent scans or print jobs yet. Use any Quick Action above to start.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.US) }
                    for (item in activities.take(8)) {
                        Surface(
                            tonalElevation = 1.dp,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = item.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = fmt.format(Date(item.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
