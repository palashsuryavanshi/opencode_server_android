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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.palash.opencodeserver.model.Project
import com.palash.opencodeserver.ui.theme.*

@Composable
fun ProjectDetailScreen(
    project: Project,
    onOpenFiles: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenChanges: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DevBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Project Overview Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DevSurface),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DevBorder, RoundedCornerShape(10.dp))
                    .testTag("project_detail_overview_card")
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
                            text = project.name,
                            style = Typography.headlineMedium,
                            color = DevTextPrimary
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(DevSurfaceElevated)
                                .border(1.dp, DevBorder, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = project.gitBranch,
                                fontFamily = DevMonospace,
                                fontSize = 11.sp,
                                color = DevAccentCyan
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = project.path,
                        fontFamily = DevMonospace,
                        fontSize = 12.sp,
                        color = DevAccentCyan
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StatPill("Language", project.language, Modifier.weight(1f))
                        StatPill("Files", "${project.fileCount} tracked", Modifier.weight(1f))
                    }
                }
            }
        }

        item {
            Text(
                text = "WORKSPACE MODULES",
                fontSize = 11.sp,
                fontFamily = DevMonospace,
                fontWeight = FontWeight.Bold,
                color = DevTextSecondary
            )
        }

        // Navigation Modules
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModuleCard(
                    title = "File Explorer",
                    description = "Browse, edit and inspect code files",
                    icon = Icons.Default.Folder,
                    onClick = onOpenFiles
                )
                ModuleCard(
                    title = "Terminal Console",
                    description = "Run commands in local bash shell",
                    icon = Icons.Default.Terminal,
                    onClick = onOpenTerminal
                )
                ModuleCard(
                    title = "Coding Sessions",
                    description = "AI coding agent and prompt threads",
                    icon = Icons.Default.SmartToy,
                    onClick = onOpenSessions
                )
                ModuleCard(
                    title = "Git & Diff Viewer",
                    description = "Inspect unstaged modifications and diffs",
                    icon = Icons.Default.Commit,
                    onClick = onOpenChanges
                )
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(DevCodeBackground)
            .border(1.dp, DevBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column {
            Text(text = label, fontSize = 10.sp, color = DevTextMuted, fontFamily = DevMonospace)
            Text(text = value, fontSize = 12.sp, color = DevTextPrimary, fontFamily = DevMonospace)
        }
    }
}

@Composable
private fun ModuleCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DevSurface),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DevBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag("module_card_${title.lowercase().replace(" ", "_")}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DevSurfaceElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = DevAccentCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column {
                    Text(
                        text = title,
                        style = Typography.titleMedium,
                        color = DevTextPrimary
                    )
                    Text(
                        text = description,
                        style = Typography.bodySmall,
                        color = DevTextSecondary
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = DevTextSecondary
            )
        }
    }
}
