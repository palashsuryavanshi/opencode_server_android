package com.palash.opencodeserver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.palash.opencodeserver.model.FileDiff
import com.palash.opencodeserver.ui.components.DiffViewerCard
import com.palash.opencodeserver.ui.theme.*

@Composable
fun GitChangesScreen(
    gitChanges: List<FileDiff>,
    onStageFile: (String) -> Unit,
    onStageAll: () -> Unit,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var commitMessage by remember { mutableStateOf("feat: implement theme toggle & settings view") }

    val totalAdditions = remember(gitChanges) { gitChanges.sumOf { it.additions } }
    val totalDeletions = remember(gitChanges) { gitChanges.sumOf { it.deletions } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DevBackground)
            .padding(horizontal = 16.dp)
            .testTag("git_changes_screen")
    ) {
        Spacer(modifier = Modifier.height(14.dp))

        // Summary Card
        Card(
            colors = CardDefaults.cardColors(containerColor = DevSurface),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, DevBorder, RoundedCornerShape(10.dp))
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
                    Column {
                        Text(
                            text = "WORKING TREE CHANGES",
                            fontSize = 11.sp,
                            fontFamily = DevMonospace,
                            fontWeight = FontWeight.Bold,
                            color = DevTextSecondary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${gitChanges.size} files modified",
                            style = Typography.titleMedium,
                            color = DevTextPrimary
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "+$totalAdditions",
                            fontFamily = DevMonospace,
                            fontSize = 12.sp,
                            color = DevTerminalGreen,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "-$totalDeletions",
                            fontFamily = DevMonospace,
                            fontSize = 12.sp,
                            color = DevAccentRed,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Stage All Button
                OutlinedButton(
                    onClick = onStageAll,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DevAccentCyan),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DevBorder),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .testTag("stage_all_button")
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Stage All Changes", fontSize = 12.sp, fontFamily = DevMonospace)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Diff Cards List
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(gitChanges) { diff ->
                DiffViewerCard(
                    fileDiff = diff,
                    initiallyExpanded = diff.filePath == "src/App.tsx",
                    onStageClick = { onStageFile(diff.filePath) }
                )
            }
        }

        // Commit Box at Bottom
        Surface(
            color = DevSurface,
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, DevBorder, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                OutlinedTextField(
                    value = commitMessage,
                    onValueChange = { commitMessage = it },
                    placeholder = { Text("Commit message...", fontSize = 12.sp, color = DevTextMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = DevTextPrimary,
                        unfocusedTextColor = DevTextPrimary,
                        focusedBorderColor = DevAccentCyan,
                        unfocusedBorderColor = DevBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        if (commitMessage.isNotBlank()) {
                            onCommit(commitMessage)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DevAccentEmerald,
                        contentColor = DevBackground
                    ),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .testTag("git_commit_button")
                ) {
                    Icon(Icons.Default.Commit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Commit Changes (Demo)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
