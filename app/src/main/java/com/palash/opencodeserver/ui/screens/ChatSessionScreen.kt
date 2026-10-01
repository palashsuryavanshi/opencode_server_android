package com.palash.opencodeserver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.palash.opencodeserver.model.ChatMessage
import com.palash.opencodeserver.model.SenderRole
import com.palash.opencodeserver.model.Session
import com.palash.opencodeserver.ui.components.DiffViewerCard
import com.palash.opencodeserver.ui.components.SessionStatusPill
import com.palash.opencodeserver.ui.components.ToolActivityCard
import com.palash.opencodeserver.ui.theme.*

@Composable
fun ChatSessionScreen(
    session: Session,
    onSendMessage: (String) -> Unit,
    onOpenTerminal: () -> Unit,
    onViewChanges: () -> Unit,
    onOpenFile: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var promptInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(session.messages.size) {
        if (session.messages.isNotEmpty()) {
            listState.animateScrollToItem(session.messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DevBackground)
            .testTag("chat_session_screen")
    ) {
        // Chat Header
        Surface(
            color = DevSurface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = session.title,
                                style = Typography.titleMedium,
                                color = DevTextPrimary
                            )
                            SessionStatusPill(status = session.status)
                        }
                        Text(
                            text = "Project: ~/projects/demo-project · ${session.messages.size} messages",
                            fontSize = 11.sp,
                            fontFamily = DevMonospace,
                            color = DevTextSecondary
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = onOpenTerminal,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = "Terminal",
                                tint = DevAccentCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onViewChanges,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Commit,
                                contentDescription = "Changes",
                                tint = DevAccentEmerald,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                HorizontalDivider(color = DevBorder, thickness = 1.dp)
            }
        }

        // Message Stream
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp)
        ) {
            items(session.messages) { message ->
                ChatMessageItem(
                    message = message,
                    onViewChanges = onViewChanges
                )
            }
        }

        HorizontalDivider(color = DevBorder, thickness = 1.dp)

        // AI Input Bar Area
        Surface(
            color = DevSurface,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // Input TextField
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DevCodeBackground)
                        .border(1.dp, DevBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    TextField(
                        value = promptInput,
                        onValueChange = { promptInput = it },
                        placeholder = {
                            Text(
                                text = "Ask OpenCode to modify your project...",
                                fontSize = 13.sp,
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
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_chat_prompt_input")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bottom actions row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { promptInput += " @src/App.tsx " },
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(DevSurfaceElevated)
                                .testTag("ai_input_mention_file")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AlternateEmail,
                                contentDescription = "Mention File",
                                tint = DevAccentCyan,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        IconButton(
                            onClick = { promptInput += " $ " },
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(DevSurfaceElevated)
                                .testTag("ai_input_terminal_cmd")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = "Terminal Command",
                                tint = DevTerminalGreen,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        IconButton(
                            onClick = { promptInput += " /git diff " },
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(DevSurfaceElevated)
                                .testTag("ai_input_attach")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AttachFile,
                                contentDescription = "Attach Diff",
                                tint = DevTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Send Button
                    val canSend = promptInput.isNotBlank()
                    Button(
                        onClick = {
                            if (canSend) {
                                onSendMessage(promptInput)
                                promptInput = ""
                            }
                        },
                        enabled = canSend,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DevAccentCyan,
                            contentColor = DevBackground,
                            disabledContainerColor = DevSurfaceElevated,
                            disabledContentColor = DevTextMuted
                        ),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("ai_chat_send_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Send",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    onViewChanges: () -> Unit
) {
    val isUser = message.role == SenderRole.USER

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Sender Label & Timestamp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (isUser) "You" else "OpenCode",
                fontFamily = DevMonospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = if (isUser) DevAccentCyan else DevAccentEmerald
            )
            Text(
                text = message.timestamp,
                fontSize = 10.sp,
                color = DevTextMuted,
                fontFamily = DevMonospace
            )
        }

        // Message Content Box
        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isUser) DevSurfaceElevated else DevSurface)
                .border(
                    1.dp,
                    if (isUser) DevAccentCyan.copy(alpha = 0.3f) else DevBorder,
                    RoundedCornerShape(8.dp)
                )
                .padding(12.dp)
        ) {
            Text(
                text = message.text,
                style = Typography.bodyMedium,
                color = DevTextPrimary
            )
        }

        // Optional Tool Activity
        if (message.toolActivity != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.widthIn(max = 340.dp)) {
                ToolActivityCard(activity = message.toolActivity)
            }
        }

        // Optional Proposed Diffs
        if (message.proposedDiffs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier.widthIn(max = 340.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                message.proposedDiffs.forEach { diff ->
                    DiffViewerCard(fileDiff = diff, initiallyExpanded = false)
                }
            }
        }
    }
}
