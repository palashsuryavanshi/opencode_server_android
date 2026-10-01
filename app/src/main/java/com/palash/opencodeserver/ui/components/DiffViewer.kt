package com.palash.opencodeserver.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.palash.opencodeserver.model.DiffLineType
import com.palash.opencodeserver.model.DiffType
import com.palash.opencodeserver.model.FileDiff
import com.palash.opencodeserver.ui.theme.*

@Composable
fun DiffViewerCard(
    fileDiff: FileDiff,
    initiallyExpanded: Boolean = false,
    onStageClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(initiallyExpanded) }

    val badgeColor = when (fileDiff.changeType) {
        DiffType.MODIFIED -> DevAccentAmber
        DiffType.ADDED -> DevAccentEmerald
        DiffType.DELETED -> DevAccentRed
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = DevSurfaceElevated),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, DevBorder, RoundedCornerShape(8.dp))
            .testTag("diff_card_${fileDiff.filePath}")
    ) {
        Column {
            // File Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // Badge (M, A, D)
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = fileDiff.changeType.symbol,
                            color = badgeColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = DevMonospace
                        )
                    }

                    Text(
                        text = fileDiff.filePath,
                        fontFamily = DevMonospace,
                        fontSize = 13.sp,
                        color = DevTextPrimary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "+${fileDiff.additions}",
                        color = DevTerminalGreen,
                        fontFamily = DevMonospace,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "-${fileDiff.deletions}",
                        color = DevAccentRed,
                        fontFamily = DevMonospace,
                        fontSize = 11.sp
                    )

                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = DevTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DevCodeBackground)
                        .border(1.dp, DevBorder.copy(alpha = 0.5f))
                ) {
                    val horizontalScroll = rememberScrollState()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(horizontalScroll)
                            .padding(vertical = 4.dp)
                    ) {
                        fileDiff.lines.forEach { line ->
                            val (bg, textColor) = when (line.type) {
                                DiffLineType.ADD -> DevDiffAddBg to DevTerminalGreen
                                DiffLineType.REMOVE -> DevDiffRemoveBg to DevAccentRed
                                DiffLineType.HEADER -> DevSurfaceElevated to DevAccentCyan
                                DiffLineType.UNCHANGED -> Color.Transparent to DevTextSecondary
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bg)
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Line Numbers
                                Text(
                                    text = (line.oldLineNo?.toString() ?: "").padStart(3, ' '),
                                    fontFamily = DevMonospace,
                                    fontSize = 11.sp,
                                    color = DevTextMuted,
                                    modifier = Modifier.width(28.dp)
                                )
                                Text(
                                    text = (line.newLineNo?.toString() ?: "").padStart(3, ' '),
                                    fontFamily = DevMonospace,
                                    fontSize = 11.sp,
                                    color = DevTextMuted,
                                    modifier = Modifier.width(28.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))

                                // Content
                                Text(
                                    text = line.text,
                                    fontFamily = DevMonospace,
                                    fontSize = 12.sp,
                                    color = textColor
                                )
                            }
                        }
                    }

                    // Optional Action Bar inside diff
                    if (onStageClick != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DevSurface)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            OutlinedButton(
                                onClick = onStageClick,
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = DevAccentCyan)
                            ) {
                                Text("Stage File", fontSize = 11.sp, fontFamily = DevMonospace)
                            }
                        }
                    }
                }
            }
        }
    }
}
