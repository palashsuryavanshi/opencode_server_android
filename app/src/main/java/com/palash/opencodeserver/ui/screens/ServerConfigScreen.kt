package com.palash.opencodeserver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.palash.opencodeserver.model.ServerConfig
import com.palash.opencodeserver.model.ServerStatus
import com.palash.opencodeserver.ui.components.ServerStatusPill
import com.palash.opencodeserver.ui.theme.*

@Composable
fun ServerConfigScreen(
    config: ServerConfig,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onRestartServer: () -> Unit,
    onToggleWakeLock: () -> Unit,
    onToggleLan: (Boolean) -> Unit,
    onUpdatePort: (Int) -> Unit,
    onUpdateAutoStart: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val isRunning = config.status == ServerStatus.RUNNING || config.status == ServerStatus.DEMO
    var portText by remember(config.port) { mutableStateOf(config.port.toString()) }
    var selectedLogLevel by remember(config.logLevel) { mutableStateOf(config.logLevel) }
    val logLevels = listOf("DEBUG", "INFO", "WARN", "ERROR")

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DevBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        // Status & Daemon Control
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DevSurface),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DevBorder, RoundedCornerShape(10.dp))
                    .testTag("server_status_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DAEMON PROCESS CONTROL",
                            fontSize = 11.sp,
                            fontFamily = DevMonospace,
                            fontWeight = FontWeight.Bold,
                            color = DevTextSecondary
                        )
                        ServerStatusPill(status = config.status)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = if (isRunning) "Daemon Running (PID ${config.pid})" else "Daemon Offline",
                        style = Typography.titleLarge,
                        color = DevTextPrimary
                    )

                    Text(
                        text = "Socket listener: http://${config.host}:${config.port}",
                        fontFamily = DevMonospace,
                        fontSize = 12.sp,
                        color = DevAccentCyan
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = if (isRunning) onStopServer else onStartServer,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRunning) DevSurfaceElevated else DevAccentCyan,
                                contentColor = if (isRunning) DevAccentRed else DevBackground
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .testTag("server_toggle_button")
                        ) {
                            Icon(
                                imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isRunning) "Stop Daemon" else "Start Daemon", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = onRestartServer,
                            enabled = isRunning,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DevTextPrimary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DevBorder),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .testTag("server_restart_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = DevAccentEmerald)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Hot Restart", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Network & Socket Binding
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DevSurface),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DevBorder, RoundedCornerShape(10.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "NETWORK & SOCKET BINDINGS",
                        fontSize = 11.sp,
                        fontFamily = DevMonospace,
                        fontWeight = FontWeight.Bold,
                        color = DevTextSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = config.host,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Host Address") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = DevTextPrimary,
                                unfocusedTextColor = DevTextPrimary,
                                focusedBorderColor = DevAccentCyan,
                                unfocusedBorderColor = DevBorder
                            ),
                            modifier = Modifier.weight(1.5f)
                        )

                        OutlinedTextField(
                            value = portText,
                            onValueChange = {
                                portText = it
                                it.toIntOrNull()?.let { p -> onUpdatePort(p) }
                            },
                            label = { Text("Port") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = DevTextPrimary,
                                unfocusedTextColor = DevTextPrimary,
                                focusedBorderColor = DevAccentCyan,
                                unfocusedBorderColor = DevBorder
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // LAN Access Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Allow LAN Connections (0.0.0.0)", style = Typography.bodyMedium, color = DevTextPrimary)
                            Text("Allows browser pairing from devices on the same Wi-Fi network", style = Typography.bodySmall, color = DevTextMuted)
                        }
                        Switch(
                            checked = config.allowLanConnections,
                            onCheckedChange = onToggleLan,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DevAccentCyan,
                                checkedTrackColor = DevAccentCyanSubtle,
                                uncheckedBorderColor = DevBorder
                            )
                        )
                    }

                    HorizontalDivider(color = DevBorder, thickness = 1.dp)

                    // Auto-start Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-start with Android App", style = Typography.bodyMedium, color = DevTextPrimary)
                            Text("Start daemon immediately upon application launch", style = Typography.bodySmall, color = DevTextMuted)
                        }
                        Switch(
                            checked = config.autoStart,
                            onCheckedChange = onUpdateAutoStart,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DevAccentCyan,
                                checkedTrackColor = DevAccentCyanSubtle,
                                uncheckedBorderColor = DevBorder
                            )
                        )
                    }
                }
            }
        }

        // Android System & Terminal Daemon Flags
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DevSurface),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DevBorder, RoundedCornerShape(10.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "ANDROID SYSTEM INTEGRATION",
                        fontSize = 11.sp,
                        fontFamily = DevMonospace,
                        fontWeight = FontWeight.Bold,
                        color = DevTextSecondary
                    )

                    // Wakelock Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Hold Partial Wakelock", style = Typography.bodyMedium, color = DevTextPrimary)
                            Text("Prevents Android OS from suspending server when phone sleeps", style = Typography.bodySmall, color = DevTextMuted)
                        }
                        Switch(
                            checked = config.wakeLockActive,
                            onCheckedChange = { onToggleWakeLock() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DevAccentEmerald,
                                checkedTrackColor = DevAccentEmeraldSubtle,
                                uncheckedBorderColor = DevBorder
                            )
                        )
                    }

                    HorizontalDivider(color = DevBorder, thickness = 1.dp)

                    // Log verbosity
                    Column {
                        Text("Daemon Log Verbosity", style = Typography.bodyMedium, color = DevTextPrimary)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            logLevels.forEach { lvl ->
                                val isSelected = lvl == selectedLogLevel
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isSelected) DevAccentCyanSubtle else DevSurfaceElevated)
                                        .border(1.dp, if (isSelected) DevAccentCyan else DevBorder, RoundedCornerShape(4.dp))
                                        .clickable { selectedLogLevel = lvl }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(lvl, fontSize = 11.sp, fontFamily = DevMonospace, color = if (isSelected) DevAccentCyan else DevTextSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Environment Details
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DevSurface),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DevBorder, RoundedCornerShape(10.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "ENVIRONMENT RUNTIME",
                        fontSize = 11.sp,
                        fontFamily = DevMonospace,
                        fontWeight = FontWeight.Bold,
                        color = DevTextSecondary
                    )

                    InfoRow("Terminal Shell", config.shell)
                    InfoRow("Runtime Engine", config.runtime)
                    InfoRow("Package Manager", config.packageManager)
                    InfoRow("OpenCode Package", config.openCodeVersion)
                    InfoRow("Root Directory", config.workingDir)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 11.sp, color = DevTextMuted, fontFamily = DevMonospace)
        Text(text = value, fontSize = 11.sp, color = DevTextPrimary, fontFamily = DevMonospace)
    }
}
