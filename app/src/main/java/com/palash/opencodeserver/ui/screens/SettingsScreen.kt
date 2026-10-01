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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.palash.opencodeserver.model.AppSettings
import com.palash.opencodeserver.ui.theme.*

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onOpenServerConfig: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DevBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        // Section: Appearance
        item {
            SettingsCategory(title = "APPEARANCE", icon = Icons.Default.Palette) {
                SettingToggleRow(
                    title = "Dark Developer Theme",
                    subtitle = "High-contrast obsidian background with semantic tokens",
                    checked = settings.darkMode,
                    onCheckedChange = { isDark ->
                        onUpdateSettings { it.copy(darkMode = isDark) }
                    }
                )
                SettingToggleRow(
                    title = "Compact Interface",
                    subtitle = "Maximize code editing area on mobile screens",
                    checked = settings.compactMode,
                    onCheckedChange = { isCompact ->
                        onUpdateSettings { it.copy(compactMode = isCompact) }
                    }
                )
                SettingChoiceRow(
                    title = "Accent Color",
                    currentValue = settings.accentColor,
                    options = listOf("Cyan", "Emerald", "Amber", "Purple"),
                    onSelect = { color ->
                        onUpdateSettings { it.copy(accentColor = color) }
                    }
                )
            }
        }

        // Section: Terminal
        item {
            SettingsCategory(title = "TERMINAL", icon = Icons.Default.Terminal) {
                SettingChoiceRow(
                    title = "Default Shell",
                    currentValue = settings.defaultShell,
                    options = listOf("bash", "zsh", "sh"),
                    onSelect = { shell ->
                        onUpdateSettings { it.copy(defaultShell = shell) }
                    }
                )
                SettingChoiceRow(
                    title = "Cursor Style",
                    currentValue = settings.cursorStyle,
                    options = listOf("Block", "Beam", "Underline"),
                    onSelect = { cursor ->
                        onUpdateSettings { it.copy(cursorStyle = cursor) }
                    }
                )
                SettingToggleRow(
                    title = "Confirm Destructive Commands",
                    subtitle = "Prompt before executing rm -rf or force pushes",
                    checked = settings.confirmDestructiveCommands,
                    onCheckedChange = { confirm ->
                        onUpdateSettings { it.copy(confirmDestructiveCommands = confirm) }
                    }
                )
            }
        }

        // Section: Code Editor
        item {
            SettingsCategory(title = "CODE EDITOR", icon = Icons.Default.Code) {
                SettingToggleRow(
                    title = "Show Line Numbers",
                    subtitle = "Display gutter line numbers next to code",
                    checked = settings.lineNumbers,
                    onCheckedChange = { lines ->
                        onUpdateSettings { it.copy(lineNumbers = lines) }
                    }
                )
                SettingToggleRow(
                    title = "Word Wrap",
                    subtitle = "Wrap long code lines without horizontal scrolling",
                    checked = settings.wordWrap,
                    onCheckedChange = { wrap ->
                        onUpdateSettings { it.copy(wordWrap = wrap) }
                    }
                )
                SettingToggleRow(
                    title = "Syntax Highlighting",
                    subtitle = "Colorize tokens for TypeScript, React, JSON",
                    checked = settings.syntaxHighlighting,
                    onCheckedChange = { syntax ->
                        onUpdateSettings { it.copy(syntaxHighlighting = syntax) }
                    }
                )
            }
        }

        // Section: AI Coding Agent
        item {
            SettingsCategory(title = "AI AGENT (OPENCODE)", icon = Icons.Default.SmartToy) {
                SettingChoiceRow(
                    title = "Reasoning Model",
                    currentValue = settings.aiModel,
                    options = listOf("opencode-deep-reasoning", "opencode-fast-code"),
                    onSelect = { model ->
                        onUpdateSettings { it.copy(aiModel = model) }
                    }
                )
                SettingToggleRow(
                    title = "Streaming Output",
                    subtitle = "Stream agent tokens in real time into chat",
                    checked = settings.streamResponses,
                    onCheckedChange = { stream ->
                        onUpdateSettings { it.copy(streamResponses = stream) }
                    }
                )
            }
        }

        // Section: Server Link
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DevSurface),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DevBorder, RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenServerConfig)
                    .testTag("settings_server_config_link")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
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
                            Icon(Icons.Default.Dns, contentDescription = null, tint = DevAccentCyan, modifier = Modifier.size(20.dp))
                        }
                        Column {
                            Text("Server Management", style = Typography.titleMedium, color = DevTextPrimary)
                            Text("Configure daemon, ports, runtime & auto-start", style = Typography.bodySmall, color = DevTextSecondary)
                        }
                    }

                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = DevTextSecondary)
                }
            }
        }
    }
}

@Composable
private fun SettingsCategory(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
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
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = DevAccentCyan, modifier = Modifier.size(16.dp))
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontFamily = DevMonospace,
                    fontWeight = FontWeight.Bold,
                    color = DevTextSecondary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = DevBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            content()
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = Typography.bodyLarge, color = DevTextPrimary)
            Text(text = subtitle, style = Typography.bodySmall, color = DevTextMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DevAccentCyan,
                checkedTrackColor = DevAccentCyanSubtle,
                uncheckedBorderColor = DevBorder
            )
        )
    }
}

@Composable
private fun SettingChoiceRow(
    title: String,
    currentValue: String,
    options: List<String>,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(text = title, style = Typography.bodyLarge, color = DevTextPrimary)
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            options.forEach { opt ->
                val isSelected = opt == currentValue
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) DevAccentCyanSubtle else DevSurfaceElevated)
                        .border(1.dp, if (isSelected) DevAccentCyan else DevBorder, RoundedCornerShape(6.dp))
                        .clickable { onSelect(opt) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = opt,
                        fontSize = 11.sp,
                        fontFamily = DevMonospace,
                        color = if (isSelected) DevAccentCyan else DevTextSecondary
                    )
                }
            }
        }
    }
}
