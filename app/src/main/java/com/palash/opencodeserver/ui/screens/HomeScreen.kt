package com.palash.opencodeserver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.palash.opencodeserver.model.Project
import com.palash.opencodeserver.model.ServerConfig
import com.palash.opencodeserver.model.ServerStatus
import com.palash.opencodeserver.model.Session
import com.palash.opencodeserver.ui.components.ServerStatusPill
import com.palash.opencodeserver.ui.components.SessionStatusPill
import com.palash.opencodeserver.ui.theme.*

@Composable
fun HomeScreen(
    currentProject: Project?,
    serverConfig: ServerConfig,
    recentSessions: List<Session>,
    onOpenProject: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenServerConfig: () -> Unit,
    onStartServerClick: () -> Unit,
    onViewAllSessions: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DevBackground)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
    ) {
        // Section: Current Project Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DevSurface),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DevBorder, RoundedCornerShape(10.dp))
                    .testTag("home_current_project_card")
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
                            text = "CURRENT PROJECT",
                            fontSize = 11.sp,
                            fontFamily = DevMonospace,
                            fontWeight = FontWeight.Bold,
                            color = DevTextSecondary
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(DevSurfaceElevated)
                                .border(1.dp, DevBorder, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = currentProject?.gitBranch ?: "main",
                                fontFamily = DevMonospace,
                                fontSize = 10.sp,
                                color = DevAccentCyan
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = currentProject?.name ?: "demo-project",
                        style = Typography.headlineSmall,
                        color = DevTextPrimary
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = currentProject?.path ?: "~/projects/demo-project",
                        fontFamily = DevMonospace,
                        fontSize = 12.sp,
                        color = DevAccentCyan
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action Buttons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onOpenProject,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DevAccentCyan,
                                contentColor = DevBackground
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .testTag("home_open_project_button")
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = onOpenTerminal,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DevTextPrimary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DevBorder),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .testTag("home_terminal_button")
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp), tint = DevAccentCyan)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Terminal", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = onOpenFiles,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DevTextPrimary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DevBorder),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .testTag("home_files_button")
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp), tint = DevAccentEmerald)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Files", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Section: OpenCode Server Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DevSurface),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DevBorder, RoundedCornerShape(10.dp))
                    .testTag("home_server_card")
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "OPENCODE SERVER",
                                fontSize = 11.sp,
                                fontFamily = DevMonospace,
                                fontWeight = FontWeight.Bold,
                                color = DevTextSecondary
                            )
                        }
                        ServerStatusPill(status = serverConfig.status)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(DevCodeBackground)
                            .border(1.dp, DevBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Host", fontSize = 10.sp, color = DevTextMuted, fontFamily = DevMonospace)
                            Text(serverConfig.host, fontSize = 12.sp, color = DevTextPrimary, fontFamily = DevMonospace)
                        }
                        Column {
                            Text("Port", fontSize = 10.sp, color = DevTextMuted, fontFamily = DevMonospace)
                            Text(serverConfig.port.toString(), fontSize = 12.sp, color = DevAccentCyan, fontFamily = DevMonospace)
                        }
                        Column {
                            Text("CLI Version", fontSize = 10.sp, color = DevTextMuted, fontFamily = DevMonospace)
                            Text(serverConfig.openCodeVersion, fontSize = 12.sp, color = DevAccentEmerald, fontFamily = DevMonospace)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isRunning = serverConfig.status == ServerStatus.RUNNING || serverConfig.status == ServerStatus.DEMO
                        Button(
                            onClick = onStartServerClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRunning) DevSurfaceElevated else DevAccentCyan,
                                contentColor = if (isRunning) DevAccentRed else DevBackground
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .testTag("home_server_start_button")
                        ) {
                            Icon(
                                imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isRunning) "Stop Server" else "Start Server",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        OutlinedButton(
                            onClick = onOpenServerConfig,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DevTextPrimary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DevBorder),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .testTag("home_server_configure_button")
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp), tint = DevTextSecondary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Configure", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Section: Terminal Environment Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DevSurface),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DevBorder, RoundedCornerShape(10.dp))
                    .testTag("home_environment_card")
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
                            text = "TERMINAL ENVIRONMENT",
                            fontSize = 11.sp,
                            fontFamily = DevMonospace,
                            fontWeight = FontWeight.Bold,
                            color = DevTextSecondary
                        )
                        Text(
                            text = "Local Demo",
                            fontSize = 11.sp,
                            fontFamily = DevMonospace,
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
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        EnvRow("Shell", serverConfig.shell)
                        EnvRow("Runtime", serverConfig.runtime)
                        EnvRow("Package Manager", serverConfig.packageManager)
                        EnvRow("Working Directory", serverConfig.workingDir)
                    }
                }
            }
        }

        // Section: Recent Sessions
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RECENT SESSIONS",
                    fontSize = 11.sp,
                    fontFamily = DevMonospace,
                    fontWeight = FontWeight.Bold,
                    color = DevTextSecondary
                )
                Text(
                    text = "View All ›",
                    fontSize = 12.sp,
                    color = DevAccentCyan,
                    modifier = Modifier.clickable(onClick = onViewAllSessions)
                )
            }
        }

        items(recentSessions.take(3)) { session ->
            Card(
                colors = CardDefaults.cardColors(containerColor = DevSurface),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DevBorder, RoundedCornerShape(8.dp))
                    .clickable { onOpenSession(session.id) }
                    .testTag("home_session_item_${session.id}")
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
                            text = session.title,
                            style = Typography.titleMedium,
                            color = DevTextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${session.timeAgo} · ${session.messageCount} messages · ${session.filesChangedCount} files changed",
                            fontFamily = DevMonospace,
                            fontSize = 11.sp,
                            color = DevTextSecondary
                        )
                    }
                    SessionStatusPill(status = session.status)
                }
            }
        }
    }
}

@Composable
private fun EnvRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 11.sp, color = DevTextMuted, fontFamily = DevMonospace)
        Text(text = value, fontSize = 11.sp, color = DevTextPrimary, fontFamily = DevMonospace)
    }
}
