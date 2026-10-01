package com.palash.opencodeserver.ui.components

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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.palash.opencodeserver.ui.theme.*

data class CommandItem(
    val title: String,
    val category: String,
    val shortcut: String,
    val icon: ImageVector,
    val action: () -> Unit
)

@Composable
fun CommandPaletteDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenProjects: () -> Unit,
    onNewSession: () -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onSearchFiles: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChanges: () -> Unit
) {
    if (!isVisible) return

    var query by remember { mutableStateOf("") }

    val commands = remember {
        listOf(
            CommandItem("Open Terminal", "Terminal", "⌃T", Icons.Default.Terminal, onOpenTerminal),
            CommandItem("New Coding Session", "AI Assistant", "⌘N", Icons.Default.SmartToy, onNewSession),
            CommandItem("Start OpenCode Server", "Server", "⌃S", Icons.Default.PlayArrow, onStartServer),
            CommandItem("Stop OpenCode Server", "Server", "⌃X", Icons.Default.Stop, onStopServer),
            CommandItem("Search Files in Project", "Files", "⌘P", Icons.Default.Folder, onSearchFiles),
            CommandItem("View Git Changes", "Version Control", "⌘G", Icons.Default.Commit, onOpenChanges),
            CommandItem("Manage Projects", "Workspace", "⌘O", Icons.Default.Workspaces, onOpenProjects),
            CommandItem("Open Settings", "Preferences", "⌘,", Icons.Default.Settings, onOpenSettings)
        )
    }

    val filteredCommands = remember(query) {
        if (query.isBlank()) commands else {
            commands.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.category.contains(query, ignoreCase = true)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = DevSurface),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, DevBorder, RoundedCornerShape(12.dp))
                .testTag("command_palette_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                // Search Input Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DevCodeBackground)
                        .border(1.dp, DevBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = ">",
                        fontFamily = DevMonospace,
                        color = DevAccentCyan,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text(
                                "Type a command or search...",
                                color = DevTextMuted,
                                fontSize = 13.sp
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
                        modifier = Modifier
                            .weight(1f)
                            .testTag("command_palette_search_input")
                    )
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { query = "" },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = DevTextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "COMMANDS",
                    fontSize = 10.sp,
                    color = DevTextMuted,
                    fontFamily = DevMonospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filteredCommands) { cmd ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    onDismiss()
                                    cmd.action()
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("command_item_${cmd.title.lowercase().replace(" ", "_")}")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = cmd.icon,
                                    contentDescription = null,
                                    tint = DevAccentCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column {
                                    Text(
                                        text = cmd.title,
                                        style = Typography.titleSmall,
                                        color = DevTextPrimary
                                    )
                                    Text(
                                        text = cmd.category,
                                        style = Typography.bodySmall,
                                        color = DevTextMuted
                                    )
                                }
                            }

                            // Shortcut pill
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(DevSurfaceElevated)
                                    .border(1.dp, DevBorder, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = cmd.shortcut,
                                    fontSize = 10.sp,
                                    fontFamily = DevMonospace,
                                    color = DevTextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
