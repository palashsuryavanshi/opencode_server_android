package com.palash.opencodeserver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.palash.opencodeserver.model.Session
import com.palash.opencodeserver.model.SessionStatus
import com.palash.opencodeserver.ui.components.EmptyStateView
import com.palash.opencodeserver.ui.components.SessionStatusPill
import com.palash.opencodeserver.ui.theme.*

@Composable
fun SessionsScreen(
    sessions: List<Session>,
    onOpenSession: (String) -> Unit,
    onCreateSession: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Active", "Recent", "Archived")
    var showNewSessionDialog by remember { mutableStateOf(false) }

    val filteredSessions = remember(sessions, selectedTab) {
        when (selectedTab) {
            0 -> sessions.filter { !it.isArchived && it.status != SessionStatus.COMPLETED }
            1 -> sessions.filter { !it.isArchived }
            2 -> sessions.filter { it.isArchived }
            else -> sessions
        }
    }

    Scaffold(
        containerColor = DevBackground,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showNewSessionDialog = true },
                containerColor = DevAccentCyan,
                contentColor = DevBackground,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("sessions_new_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Session")
            }
        },
        modifier = modifier.testTag("sessions_screen")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(14.dp))

            // Tab Selector
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = DevSurface,
                contentColor = DevAccentCyan,
                divider = { HorizontalDivider(color = DevBorder, thickness = 1.dp) },
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, DevBorder, RoundedCornerShape(8.dp))
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                fontSize = 12.sp,
                                fontFamily = DevMonospace,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == index) DevAccentCyan else DevTextSecondary
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (filteredSessions.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Default.SmartToy,
                    title = "No Coding Sessions",
                    description = "Start a new OpenCode session to inspect and generate project changes.",
                    buttonText = "New Session",
                    onButtonClick = { showNewSessionDialog = true }
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(filteredSessions) { session ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = DevSurface),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, DevBorder, RoundedCornerShape(10.dp))
                                .clickable { onOpenSession(session.id) }
                                .testTag("session_card_${session.id}")
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
                                    Text(
                                        text = session.title,
                                        style = Typography.titleMedium,
                                        color = DevTextPrimary
                                    )
                                    SessionStatusPill(status = session.status)
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = session.timeAgo,
                                        fontFamily = DevMonospace,
                                        fontSize = 11.sp,
                                        color = DevTextSecondary
                                    )

                                    Text(
                                        text = "${session.messageCount} messages · ${session.filesChangedCount} files changed",
                                        fontFamily = DevMonospace,
                                        fontSize = 11.sp,
                                        color = DevAccentCyan
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewSessionDialog) {
        CreateSessionDialog(
            onDismiss = { showNewSessionDialog = false },
            onConfirm = { title ->
                onCreateSession(title)
                showNewSessionDialog = false
            }
        )
    }
}

@Composable
fun CreateSessionDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String) -> Unit
) {
    var title by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = DevSurface),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, DevBorder, RoundedCornerShape(12.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "New Coding Session",
                    style = Typography.titleLarge,
                    color = DevTextPrimary
                )
                Text(
                    text = "Start a fresh agent context for demo-project",
                    style = Typography.bodySmall,
                    color = DevTextSecondary
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Session Goal / Title") },
                    placeholder = { Text("e.g. Implement authentication flow") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = DevTextPrimary,
                        unfocusedTextColor = DevTextPrimary,
                        focusedBorderColor = DevAccentCyan,
                        unfocusedBorderColor = DevBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = DevTextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (title.isNotBlank()) {
                                onConfirm(title)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DevAccentCyan,
                            contentColor = DevBackground
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Start Session")
                    }
                }
            }
        }
    }
}
