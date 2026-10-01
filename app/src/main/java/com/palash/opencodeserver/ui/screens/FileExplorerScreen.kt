package com.palash.opencodeserver.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.palash.opencodeserver.model.FileNode
import com.palash.opencodeserver.ui.theme.*

@Composable
fun FileExplorerScreen(
    currentPath: String,
    files: List<FileNode>,
    expandedFolders: Set<String>,
    onToggleFolder: (String) -> Unit,
    onOpenFile: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DevBackground)
            .testTag("file_explorer_screen")
    ) {
        // Current Path Header
        Surface(
            color = DevSurface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = DevAccentCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = currentPath,
                        fontFamily = DevMonospace,
                        fontSize = 12.sp,
                        color = DevTextPrimary,
                        fontWeight = FontWeight.Medium
                    )
                }
                HorizontalDivider(color = DevBorder, thickness = 1.dp)
            }
        }

        // Tree List
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(files) { node ->
                FileNodeItem(
                    node = node,
                    depth = 0,
                    expandedFolders = expandedFolders,
                    onToggleFolder = onToggleFolder,
                    onOpenFile = onOpenFile
                )
            }
        }
    }
}

@Composable
private fun FileNodeItem(
    node: FileNode,
    depth: Int,
    expandedFolders: Set<String>,
    onToggleFolder: (String) -> Unit,
    onOpenFile: (String, String) -> Unit
) {
    val isExpanded = expandedFolders.contains(node.path)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable {
                    if (node.isDirectory) {
                        onToggleFolder(node.path)
                    } else {
                        onOpenFile(node.path, node.name)
                    }
                }
                .padding(
                    start = (depth * 16 + 8).dp,
                    end = 8.dp,
                    top = 8.dp,
                    bottom = 8.dp
                )
                .testTag("file_node_${node.name}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (node.isDirectory) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = DevTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.width(16.dp))
                }

                Icon(
                    imageVector = getFileIcon(node),
                    contentDescription = null,
                    tint = if (node.isDirectory) DevAccentAmber else DevAccentCyan,
                    modifier = Modifier.size(18.dp)
                )

                Text(
                    text = node.name,
                    fontFamily = DevMonospace,
                    fontSize = 12.sp,
                    color = if (node.isDirectory) DevTextPrimary else DevTextSecondary,
                    fontWeight = if (node.isDirectory) FontWeight.Medium else FontWeight.Normal
                )
            }

            if (!node.isDirectory && node.size != null) {
                Text(
                    text = node.size,
                    fontFamily = DevMonospace,
                    fontSize = 10.sp,
                    color = DevTextMuted
                )
            }
        }

        // Subtree if expanded
        if (node.isDirectory) {
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    node.children.forEach { child ->
                        FileNodeItem(
                            node = child,
                            depth = depth + 1,
                            expandedFolders = expandedFolders,
                            onToggleFolder = onToggleFolder,
                            onOpenFile = onOpenFile
                        )
                    }
                }
            }
        }
    }
}

private fun getFileIcon(node: FileNode): ImageVector {
    if (node.isDirectory) return Icons.Default.Folder
    return when {
        node.name.endsWith(".tsx") || node.name.endsWith(".ts") || node.name.endsWith(".js") -> Icons.Default.Code
        node.name.endsWith(".json") -> Icons.Default.DataObject
        node.name.endsWith(".css") -> Icons.Default.Brush
        node.name.endsWith(".md") -> Icons.Default.Description
        else -> Icons.Default.InsertDriveFile
    }
}
