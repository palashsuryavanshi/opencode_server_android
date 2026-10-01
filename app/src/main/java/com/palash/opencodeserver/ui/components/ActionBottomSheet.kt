package com.palash.opencodeserver.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.palash.opencodeserver.model.Project
import com.palash.opencodeserver.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectActionBottomSheet(
    project: Project?,
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onOpenProject: (Project) -> Unit,
    onOpenTerminal: (Project) -> Unit,
    onOpenFiles: (Project) -> Unit,
    onViewChanges: (Project) -> Unit
) {
    if (!isVisible || project == null) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DevSurface,
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = DevBorder) },
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.testTag("project_action_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Text(
                text = project.name,
                style = Typography.titleLarge,
                color = DevTextPrimary
            )
            Text(
                text = project.path,
                fontFamily = DevMonospace,
                fontSize = 11.sp,
                color = DevTextSecondary
            )

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = DevBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(6.dp))

            ActionItem(
                icon = Icons.Default.Launch,
                label = "Open Project Overview",
                onClick = {
                    onDismiss()
                    onOpenProject(project)
                }
            )
            ActionItem(
                icon = Icons.Default.Terminal,
                label = "Launch Terminal",
                onClick = {
                    onDismiss()
                    onOpenTerminal(project)
                }
            )
            ActionItem(
                icon = Icons.Default.Folder,
                label = "Browse Files",
                onClick = {
                    onDismiss()
                    onOpenFiles(project)
                }
            )
            ActionItem(
                icon = Icons.Default.Commit,
                label = "View Git Changes",
                onClick = {
                    onDismiss()
                    onViewChanges(project)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = DevAccentCyan,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            style = Typography.bodyLarge,
            color = DevTextPrimary,
            fontWeight = FontWeight.Medium
        )
    }
}
