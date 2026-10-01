package com.palash.opencodeserver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.palash.opencodeserver.ui.theme.*

@Composable
fun CodeEditorScreen(
    fileName: String,
    filePath: String,
    content: String,
    isModified: Boolean,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    val lines = remember(content) { content.lines() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DevCodeBackground)
            .testTag("code_editor_screen")
    ) {
        // Top Editor Info Bar
        Surface(
            color = DevSurface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = DevAccentCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = fileName,
                            fontFamily = DevMonospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = DevTextPrimary
                        )

                        // Modified indicator
                        if (isModified) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(DevAccentAmber)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${lines.size} lines",
                            fontFamily = DevMonospace,
                            fontSize = 11.sp,
                            color = DevTextMuted
                        )

                        IconButton(
                            onClick = onSave,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("code_editor_save_icon_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = "Save",
                                tint = if (isModified) DevAccentCyan else DevTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                HorizontalDivider(color = DevBorder, thickness = 1.dp)
            }
        }

        // Code Editor Canvas with Line Numbers
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(verticalScroll)
        ) {
            // Line numbers column
            Column(
                modifier = Modifier
                    .width(42.dp)
                    .background(DevSurfaceElevated.copy(alpha = 0.4f))
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.End
            ) {
                for (i in 1..lines.size) {
                    Text(
                        text = "$i ",
                        fontFamily = DevMonospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = DevTextMuted,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }

            // Editable Code Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(horizontalScroll)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                BasicTextField(
                    value = content,
                    onValueChange = onContentChange,
                    textStyle = CodeTextStyle.copy(color = DevTextPrimary),
                    cursorBrush = SolidColor(DevAccentCyan),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("code_editor_text_field")
                )
            }
        }

        HorizontalDivider(color = DevBorder, thickness = 1.dp)

        // Bottom Mobile Toolbar: Undo, Redo, Find, Go To, Save
        Surface(
            color = DevSurface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onUndo, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Undo, contentDescription = "Undo", tint = DevTextSecondary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onRedo, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Redo, contentDescription = "Redo", tint = DevTextSecondary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { /* Find */ }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.FindInPage, contentDescription = "Find", tint = DevTextSecondary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { /* Go to line */ }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.FormatListNumbered, contentDescription = "Go to line", tint = DevTextSecondary, modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = onSave,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("code_editor_bottom_save_button")
                ) {
                    Icon(
                        Icons.Default.Save,
                        contentDescription = "Save file",
                        tint = if (isModified) DevAccentCyan else DevTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
