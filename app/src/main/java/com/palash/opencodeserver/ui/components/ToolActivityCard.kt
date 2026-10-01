package com.palash.opencodeserver.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.palash.opencodeserver.model.ToolActivity
import com.palash.opencodeserver.ui.theme.*

@Composable
fun ToolActivityCard(
    activity: ToolActivity,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DevSurfaceElevated)
            .border(1.dp, DevBorder, RoundedCornerShape(8.dp))
            .clickable { isExpanded = !isExpanded }
            .padding(10.dp)
            .testTag("tool_activity_card")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(DevAccentCyanSubtle),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (activity.isCompleted) Icons.Default.CheckCircle else Icons.Default.Terminal,
                        contentDescription = null,
                        tint = if (activity.isCompleted) DevAccentEmerald else DevAccentCyan,
                        modifier = Modifier.size(14.dp)
                    )
                }

                Column {
                    Text(
                        text = activity.title,
                        style = Typography.titleSmall,
                        color = DevTextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = activity.targetPath,
                        fontFamily = DevMonospace,
                        fontSize = 11.sp,
                        color = DevAccentCyan
                    )
                }
            }

            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = DevTextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(DevCodeBackground, RoundedCornerShape(6.dp))
                    .border(1.dp, DevBorder, RoundedCornerShape(6.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                activity.details.forEach { detail ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "›",
                            fontFamily = DevMonospace,
                            color = DevAccentCyan,
                            fontSize = 12.sp
                        )
                        Text(
                            text = detail,
                            fontFamily = DevMonospace,
                            color = DevTextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
