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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.palash.opencodeserver.model.ConnectionStatus
import com.palash.opencodeserver.ui.components.StatusPill
import com.palash.opencodeserver.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(
    projectName: String,
    terminalLines: List<String>,
    inputCommand: String,
    connectionStatus: ConnectionStatus,
    onInputChange: (String) -> Unit,
    onSendCommand: (String?) -> Unit,
    onClearTerminal: () -> Unit,
    onCopyOutput: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var searchOverlayVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val shortcutPills = listOf("ls", "git status", "opencode status", "npm run dev", "clear")

    LaunchedEffect(terminalLines.size) {
        if (terminalLines.isNotEmpty()) {
            listState.animateScrollToItem(terminalLines.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DevCodeBackground)
            .testTag("terminal_screen")
    ) {
        // Terminal Top Toolbar
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
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = DevAccentCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = projectName,
                            fontFamily = DevMonospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = DevTextPrimary
                        )
                        StatusPill(status = connectionStatus)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = { searchOverlayVisible = !searchOverlayVisible },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("terminal_search_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search Terminal",
                                tint = DevTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = onCopyOutput,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("terminal_copy_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Terminal",
                                tint = DevTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = onClearTerminal,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("terminal_clear_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Clear Terminal",
                                tint = DevTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                HorizontalDivider(color = DevBorder, thickness = 1.dp)
            }
        }

        // Optional In-terminal Search Bar
        if (searchOverlayVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DevSurfaceElevated)
                    .border(1.dp, DevBorder)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter terminal lines...", fontSize = 12.sp, color = DevTextMuted) },
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
                IconButton(
                    onClick = { searchOverlayVisible = false },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = DevTextSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }

        // Terminal Console Area
        val linesToDisplay = remember(terminalLines, searchQuery) {
            if (searchQuery.isBlank()) terminalLines else {
                terminalLines.filter { it.contains(searchQuery, ignoreCase = true) }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(linesToDisplay) { line ->
                val (textColor, isCommand) = when {
                    line.startsWith("$ ") -> DevTerminalGreen to true
                    line.startsWith("drwx") -> DevTerminalBlue to false
                    line.startsWith("-rw-") -> DevTextPrimary to false
                    line.contains("modified:") -> DevAccentAmber to false
                    line.contains("Untracked") -> DevAccentRed to false
                    line.contains("OpenCode") -> DevAccentCyan to false
                    else -> DevTextSecondary to false
                }

                Text(
                    text = line,
                    color = textColor,
                    fontFamily = DevMonospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontWeight = if (isCommand) FontWeight.Bold else FontWeight.Normal
                )
            }
        }

        // Quick Command Shortcut Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DevSurface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            shortcutPills.forEach { pill ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(DevSurfaceElevated)
                        .border(1.dp, DevBorder, RoundedCornerShape(4.dp))
                        .clickable { onSendCommand(pill) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("shortcut_pill_$pill")
                ) {
                    Text(
                        text = pill,
                        fontSize = 11.sp,
                        fontFamily = DevMonospace,
                        color = DevAccentCyan
                    )
                }
            }
        }

        HorizontalDivider(color = DevBorder, thickness = 1.dp)

        // Terminal Input Bar
        Surface(
            color = DevSurface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "$",
                    fontFamily = DevMonospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = DevTerminalGreen,
                    modifier = Modifier.padding(start = 6.dp)
                )

                TextField(
                    value = inputCommand,
                    onValueChange = onInputChange,
                    placeholder = {
                        Text(
                            text = "Enter terminal command...",
                            fontSize = 12.sp,
                            fontFamily = DevMonospace,
                            color = DevTextMuted
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedTextColor = DevTextPrimary,
                        unfocusedTextColor = DevTextPrimary
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            onSendCommand(null)
                        }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("terminal_input_field")
                )

                IconButton(
                    onClick = { onSendCommand(null) },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (inputCommand.isNotBlank()) DevAccentCyan else DevSurfaceElevated)
                        .testTag("terminal_send_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Run Command",
                        tint = if (inputCommand.isNotBlank()) DevBackground else DevTextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
