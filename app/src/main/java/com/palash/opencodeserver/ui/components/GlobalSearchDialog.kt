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
import com.palash.opencodeserver.model.Project
import com.palash.opencodeserver.model.Session
import com.palash.opencodeserver.ui.theme.*

data class SearchResult(
    val title: String,
    val subtitle: String,
    val type: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
fun GlobalSearchDialog(
    isVisible: Boolean,
    projects: List<Project>,
    sessions: List<Session>,
    onDismiss: () -> Unit,
    onOpenFile: (String, String) -> Unit,
    onOpenSession: (String) -> Unit,
    onSelectProject: (String) -> Unit
) {
    if (!isVisible) return

    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    val categories = listOf("All", "Files", "Sessions", "Projects")

    val fileItems = remember {
        listOf(
            "src/App.tsx" to "App.tsx",
            "src/components/Settings.tsx" to "Settings.tsx",
            "src/components/Nav.tsx" to "Nav.tsx",
            "src/components/MobileMenu.tsx" to "MobileMenu.tsx",
            "package.json" to "package.json",
            "README.md" to "README.md",
            "vite.config.ts" to "vite.config.ts"
        )
    }

    val allResults = remember(projects, sessions, query, selectedCategory) {
        val list = mutableListOf<SearchResult>()

        if (selectedCategory == "All" || selectedCategory == "Files") {
            fileItems.filter { it.first.contains(query, ignoreCase = true) }.forEach { (path, name) ->
                list.add(
                    SearchResult(
                        title = name,
                        subtitle = path,
                        type = "File",
                        icon = Icons.Default.InsertDriveFile,
                        onClick = {
                            onDismiss()
                            onOpenFile(path, name)
                        }
                    )
                )
            }
        }

        if (selectedCategory == "All" || selectedCategory == "Sessions") {
            sessions.filter { it.title.contains(query, ignoreCase = true) }.forEach { sess ->
                list.add(
                    SearchResult(
                        title = sess.title,
                        subtitle = "${sess.timeAgo} · ${sess.messageCount} msgs",
                        type = "Session",
                        icon = Icons.Default.SmartToy,
                        onClick = {
                            onDismiss()
                            onOpenSession(sess.id)
                        }
                    )
                )
            }
        }

        if (selectedCategory == "All" || selectedCategory == "Projects") {
            projects.filter { it.name.contains(query, ignoreCase = true) }.forEach { proj ->
                list.add(
                    SearchResult(
                        title = proj.name,
                        subtitle = proj.path,
                        type = "Project",
                        icon = Icons.Default.Folder,
                        onClick = {
                            onDismiss()
                            onSelectProject(proj.id)
                        }
                    )
                )
            }
        }

        list
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = DevSurface),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, DevBorder, RoundedCornerShape(12.dp))
                .testTag("global_search_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                // Search Input
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
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = DevAccentCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text(
                                "Search files, sessions, projects...",
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
                            .testTag("global_search_input")
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

                // Filter Category Chips
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    categories.forEach { cat ->
                        val isSelected = cat == selectedCategory
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) DevAccentCyanSubtle else DevSurfaceElevated)
                                .border(
                                    1.dp,
                                    if (isSelected) DevAccentCyan else DevBorder,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable { selectedCategory = cat }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = cat,
                                fontSize = 11.sp,
                                color = if (isSelected) DevAccentCyan else DevTextSecondary,
                                fontFamily = DevMonospace
                            )
                        }
                    }
                }

                // Results List
                if (allResults.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No results found for '$query'",
                            color = DevTextMuted,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(allResults) { result ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { result.onClick() }
                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                                    .testTag("search_result_${result.title.lowercase()}")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = result.icon,
                                        contentDescription = null,
                                        tint = DevAccentCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Column {
                                        Text(
                                            text = result.title,
                                            style = Typography.titleSmall,
                                            color = DevTextPrimary
                                        )
                                        Text(
                                            text = result.subtitle,
                                            style = Typography.bodySmall,
                                            color = DevTextMuted,
                                            fontFamily = DevMonospace
                                        )
                                    }
                                }

                                Text(
                                    text = result.type,
                                    fontSize = 10.sp,
                                    color = DevTextSecondary,
                                    fontFamily = DevMonospace,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(DevSurfaceElevated)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
