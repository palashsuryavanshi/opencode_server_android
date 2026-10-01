package com.palash.opencodeserver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.palash.opencodeserver.model.*
import com.palash.opencodeserver.ui.components.ServerStatusPill
import com.palash.opencodeserver.ui.theme.*

@Composable
fun ServerDashboardScreen(
    serverConfig: ServerConfig,
    healthChecks: List<ServerHealthCheck>,
    recentLogs: List<ServerLogEntry>,
    currentProject: Project?,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onRestartServer: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenEndpoints: () -> Unit,
    onOpenConfig: () -> Unit,
    onOpenWorkspaces: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRunning = serverConfig.status == ServerStatus.RUNNING || serverConfig.status == ServerStatus.DEMO

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DevBackground)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
    ) {
        // Hero Server Controller Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DevSurface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (isRunning) DevAccentEmerald.copy(alpha = 0.4f) else DevBorder,
                        RoundedCornerShape(12.dp)
                    )
                    .testTag("server_dashboard_hero_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Header Row with Status Pill
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isRunning) DevAccentEmerald else DevTextMuted)
                            )
                            Text(
                                text = "LOCAL OPENCODE DAEMON",
                                fontSize = 11.sp,
                                fontFamily = DevMonospace,
                                fontWeight = FontWeight.Bold,
                                color = DevTextSecondary
                            )
                        }
                        ServerStatusPill(status = serverConfig.status)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Host & Port Display
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DevCodeBackground)
                            .border(1.dp, DevBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "SOCKET BINDING",
                                fontSize = 9.sp,
                                fontFamily = DevMonospace,
                                color = DevTextMuted
                            )
                            Text(
                                text = "http://${serverConfig.host}:${serverConfig.port}",
                                fontFamily = DevMonospace,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DevAccentCyan
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(DevSurfaceElevated)
                                .border(1.dp, DevBorder, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (serverConfig.pid > 0) "PID ${serverConfig.pid}" else "PID INACTIVE",
                                fontFamily = DevMonospace,
                                fontSize = 11.sp,
                                color = if (serverConfig.pid > 0) DevTerminalGreen else DevTextMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Big Power & Control Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (isRunning) onStopServer() else onStartServer()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRunning) DevSurfaceElevated else DevAccentCyan,
                                contentColor = if (isRunning) DevAccentRed else DevBackground
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(44.dp)
                                .testTag("server_power_toggle_button")
                        ) {
                            Icon(
                                imageVector = if (isRunning) Icons.Default.PowerSettingsNew else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isRunning) "Stop Daemon" else "Start Daemon",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = onRestartServer,
                            enabled = isRunning,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = DevTextPrimary,
                                disabledContentColor = DevTextMuted
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DevBorder),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("server_restart_quick_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = DevAccentEmerald)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Restart", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Live Telemetry Grid (CPU, RAM, Connections, PTY)
        item {
            Text(
                text = "LIVE TELEMETRY & RESOURCES",
                fontSize = 11.sp,
                fontFamily = DevMonospace,
                fontWeight = FontWeight.Bold,
                color = DevTextSecondary
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryCard(
                    title = "CPU Load",
                    value = if (isRunning) "${serverConfig.cpuUsagePercent}%" else "0.0%",
                    subtext = "2 worker threads",
                    progress = if (isRunning) (serverConfig.cpuUsagePercent / 100f).coerceIn(0.02f, 1f) else 0f,
                    progressColor = DevAccentCyan,
                    modifier = Modifier.weight(1f)
                )

                TelemetryCard(
                    title = "RAM Resident",
                    value = if (isRunning) "${serverConfig.memoryUsageMb} MB" else "0 MB",
                    subtext = "Limit: ${serverConfig.memoryLimitMb.toInt()} MB",
                    progress = if (isRunning) (serverConfig.memoryUsageMb / serverConfig.memoryLimitMb) else 0f,
                    progressColor = DevAccentEmerald,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryCard(
                    title = "Active Sockets",
                    value = if (isRunning) "${serverConfig.activeConnections}" else "0",
                    subtext = "HTTP/WS Clients",
                    progress = if (isRunning) 0.35f else 0f,
                    progressColor = DevAccentPurple,
                    modifier = Modifier.weight(1f)
                )

                TelemetryCard(
                    title = "PTY Subsystem",
                    value = if (isRunning) "${serverConfig.ptySessionsCount} Session" else "Inactive",
                    subtext = "/dev/pts/3 attached",
                    progress = if (isRunning) 0.5f else 0f,
                    progressColor = DevAccentAmber,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Quick Subsystem Navigation Bar
        item {
            Text(
                text = "SERVER SUBSYSTEMS",
                fontSize = 11.sp,
                fontFamily = DevMonospace,
                fontWeight = FontWeight.Bold,
                color = DevTextSecondary
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SubsystemNavRow(
                    icon = Icons.Default.Article,
                    title = "Real-time Daemon Logs",
                    subtitle = "Stream stdout/stderr with level filtering",
                    badge = "${recentLogs.size} logs",
                    onClick = onOpenLogs
                )
                SubsystemNavRow(
                    icon = Icons.Default.Terminal,
                    title = "PTY Terminal Emulator",
                    subtitle = "Interactive opencodectl shell console",
                    badge = "bash",
                    onClick = onOpenTerminal
                )
                SubsystemNavRow(
                    icon = Icons.Default.Api,
                    title = "API & WebSocket Endpoints",
                    subtitle = "Inspect and test /api/status, /api/health, /ws/pty",
                    badge = "6 routes",
                    onClick = onOpenEndpoints
                )
                SubsystemNavRow(
                    icon = Icons.Default.Tune,
                    title = "Daemon Configuration",
                    subtitle = "Port bindings, wakelock & startup environment",
                    badge = ":${serverConfig.port}",
                    onClick = onOpenConfig
                )
            }
        }

        // Subsystem Health Checks
        item {
            Text(
                text = "SUBSYSTEM HEALTH STATUS",
                fontSize = 11.sp,
                fontFamily = DevMonospace,
                fontWeight = FontWeight.Bold,
                color = DevTextSecondary
            )
        }

        items(healthChecks) { check ->
            Card(
                colors = CardDefaults.cardColors(containerColor = DevSurface),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DevBorder, RoundedCornerShape(8.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (check.isHealthy && isRunning) DevAccentEmeraldSubtle else DevSurfaceElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (check.isHealthy && isRunning) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                contentDescription = null,
                                tint = if (check.isHealthy && isRunning) DevAccentEmerald else DevTextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Column {
                            Text(
                                text = check.name,
                                style = Typography.titleSmall,
                                color = DevTextPrimary
                            )
                            Text(
                                text = check.details,
                                fontFamily = DevMonospace,
                                fontSize = 11.sp,
                                color = DevTextSecondary
                            )
                        }
                    }

                    Text(
                        text = if (isRunning) "${check.latencyMs}ms" else "--",
                        fontFamily = DevMonospace,
                        fontSize = 11.sp,
                        color = if (check.isHealthy && isRunning) DevTerminalGreen else DevTextMuted
                    )
                }
            }
        }

        // Live Log Stream Teaser Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DevSurface),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DevBorder, RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenLogs)
                    .testTag("server_dashboard_logs_teaser")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = null, tint = DevAccentCyan, modifier = Modifier.size(16.dp))
                            Text(
                                text = "LATEST LOG EVENTS",
                                fontSize = 11.sp,
                                fontFamily = DevMonospace,
                                fontWeight = FontWeight.Bold,
                                color = DevTextSecondary
                            )
                        }
                        Text(
                            text = "View Stream ›",
                            fontSize = 12.sp,
                            color = DevAccentCyan
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(DevCodeBackground)
                            .border(1.dp, DevBorder, RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val preview = recentLogs.takeLast(3)
                        if (preview.isEmpty()) {
                            Text("No recent log events", color = DevTextMuted, fontSize = 11.sp, fontFamily = DevMonospace)
                        } else {
                            preview.forEach { log ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(log.timestamp, fontSize = 10.sp, color = DevTextMuted, fontFamily = DevMonospace)
                                    Text("[${log.subsystem}]", fontSize = 10.sp, color = DevAccentCyan, fontFamily = DevMonospace)
                                    Text(
                                        text = log.message,
                                        fontSize = 11.sp,
                                        color = DevTextPrimary,
                                        fontFamily = DevMonospace,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Subordinate: Active Local Workspace
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DevSurface),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DevBorder, RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenWorkspaces)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(DevSurfaceElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = DevAccentAmber, modifier = Modifier.size(18.dp))
                        }
                        Column {
                            Text(
                                text = "Served Workspace: ${currentProject?.name ?: "demo-project"}",
                                style = Typography.titleSmall,
                                color = DevTextPrimary
                            )
                            Text(
                                text = currentProject?.path ?: "~/projects/demo-project",
                                fontFamily = DevMonospace,
                                fontSize = 11.sp,
                                color = DevTextSecondary
                            )
                        }
                    }

                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = DevTextSecondary)
                }
            }
        }
    }
}

@Composable
private fun TelemetryCard(
    title: String,
    value: String,
    subtext: String,
    progress: Float,
    progressColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DevSurface),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .border(1.dp, DevBorder, RoundedCornerShape(8.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = title,
                fontSize = 11.sp,
                fontFamily = DevMonospace,
                color = DevTextSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 18.sp,
                fontFamily = DevMonospace,
                fontWeight = FontWeight.Bold,
                color = DevTextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = progressColor,
                trackColor = DevSurfaceElevated
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtext,
                fontSize = 10.sp,
                fontFamily = DevMonospace,
                color = DevTextMuted
            )
        }
    }
}

@Composable
private fun SubsystemNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DevSurface),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DevBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(DevSurfaceElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = icon, contentDescription = null, tint = DevAccentCyan, modifier = Modifier.size(18.dp))
                }
                Column {
                    Text(text = title, style = Typography.titleSmall, color = DevTextPrimary)
                    Text(text = subtitle, style = Typography.bodySmall, color = DevTextMuted)
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(DevSurfaceElevated)
                    .border(1.dp, DevBorder, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(text = badge, fontSize = 10.sp, fontFamily = DevMonospace, color = DevAccentCyan)
            }
        }
    }
}
