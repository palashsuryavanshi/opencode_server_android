package com.palash.opencodeserver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import com.palash.opencodeserver.model.LogLevel
import com.palash.opencodeserver.model.ServerLogEntry
import com.palash.opencodeserver.ui.theme.*

@Composable
fun ServerLogsScreen(
    logs: List<ServerLogEntry>,
    logLevelFilter: String,
    subsystemFilter: String,
    searchQuery: String,
    isPaused: Boolean,
    onLogLevelSelected: (String) -> Unit,
    onSubsystemSelected: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onTogglePause: () -> Unit,
    onClearLogs: () -> Unit,
    onCopyLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    var searchBarVisible by remember { mutableStateOf(false) }

    val levels = listOf("ALL", "INFO", "WARN", "ERROR", "DEBUG")
    val subsystems = listOf("ALL", "daemon", "pty", "http", "ws", "bridge")

    val filteredLogs = remember(logs, logLevelFilter, subsystemFilter, searchQuery) {
        logs.filter { log ->
            val matchLevel = logLevelFilter == "ALL" || log.level.name.equals(logLevelFilter, ignoreCase = true)
            val matchSubsystem = subsystemFilter == "ALL" || log.subsystem.equals(subsystemFilter, ignoreCase = true)
            val matchSearch = searchQuery.isBlank() || log.message.contains(searchQuery, ignoreCase = true) || log.subsystem.contains(searchQuery, ignoreCase = true)
            matchLevel && matchSubsystem && matchSearch
        }
    }

    LaunchedEffect(filteredLogs.size) {
        if (!isPaused && filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DevCodeBackground)
            .testTag("server_logs_screen")
    ) {
        // Log Viewer Controls Toolbar
        Surface(
            color = DevSurface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "DAEMON STDOUT / STDERR",
                            fontFamily = DevMonospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = DevTextSecondary
                        )

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isPaused) DevAccentAmber.copy(alpha = 0.2f) else DevAccentEmeraldSubtle)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isPaused) "PAUSED" else "STREAMING",
                                fontFamily = DevMonospace,
                                fontSize = 10.sp,
                                color = if (isPaused) DevAccentAmber else DevTerminalGreen
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = { searchBarVisible = !searchBarVisible },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = if (searchQuery.isNotEmpty()) DevAccentCyan else DevTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = onTogglePause,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (isPaused) "Resume" else "Pause",
                                tint = if (isPaused) DevAccentEmerald else DevTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = onCopyLogs,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = DevTextSecondary, modifier = Modifier.size(18.dp))
                        }

                        IconButton(
                            onClick = onClearLogs,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Clear", tint = DevTextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // Search Bar dropdown
                if (searchBarVisible) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DevSurfaceElevated)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = { Text("Search logs...", fontSize = 12.sp, color = DevTextMuted) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                focusedTextColor = DevTextPrimary,
                                unfocusedTextColor = DevTextPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = DevTextMuted, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                // Filter Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "LEVEL:",
                        fontSize = 10.sp,
                        fontFamily = DevMonospace,
                        color = DevTextMuted,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )

                    levels.forEach { lvl ->
                        val isSelected = lvl == logLevelFilter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSelected) DevAccentCyanSubtle else DevSurfaceElevated)
                                .border(1.dp, if (isSelected) DevAccentCyan else DevBorder, RoundedCornerShape(4.dp))
                                .clickable { onLogLevelSelected(lvl) }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = lvl,
                                fontSize = 10.sp,
                                fontFamily = DevMonospace,
                                color = if (isSelected) DevAccentCyan else DevTextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = "SUBSYSTEM:",
                        fontSize = 10.sp,
                        fontFamily = DevMonospace,
                        color = DevTextMuted,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )

                    subsystems.forEach { sub ->
                        val isSelected = sub == subsystemFilter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSelected) DevAccentEmeraldSubtle else DevSurfaceElevated)
                                .border(1.dp, if (isSelected) DevAccentEmerald else DevBorder, RoundedCornerShape(4.dp))
                                .clickable { onSubsystemSelected(sub) }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = sub,
                                fontSize = 10.sp,
                                fontFamily = DevMonospace,
                                color = if (isSelected) DevAccentEmerald else DevTextSecondary
                            )
                        }
                    }
                }

                HorizontalDivider(color = DevBorder, thickness = 1.dp)
            }
        }

        // Live Logs Stream
        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No matching log entries found",
                    color = DevTextMuted,
                    fontSize = 12.sp,
                    fontFamily = DevMonospace
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(filteredLogs) { log ->
                    val levelColor = when (log.level) {
                        LogLevel.INFO -> DevTerminalGreen
                        LogLevel.DEBUG -> DevAccentCyan
                        LogLevel.WARN -> DevAccentAmber
                        LogLevel.ERROR -> DevAccentRed
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = log.timestamp,
                            fontFamily = DevMonospace,
                            fontSize = 11.sp,
                            color = DevTextMuted,
                            modifier = Modifier.width(54.dp)
                        )

                        Text(
                            text = log.level.name.padEnd(5, ' '),
                            fontFamily = DevMonospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = levelColor,
                            modifier = Modifier.width(42.dp)
                        )

                        Text(
                            text = "[${log.subsystem}]",
                            fontFamily = DevMonospace,
                            fontSize = 11.sp,
                            color = DevAccentPurple,
                            modifier = Modifier.width(62.dp)
                        )

                        Text(
                            text = log.message,
                            fontFamily = DevMonospace,
                            fontSize = 11.sp,
                            color = DevTextPrimary,
                            lineHeight = 16.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
