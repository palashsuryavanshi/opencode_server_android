package com.palash.opencodeserver.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.palash.opencodeserver.R
import com.palash.opencodeserver.model.ConnectionStatus
import com.palash.opencodeserver.ui.theme.*

@Composable
fun AppTopBar(
    title: String = "OpenCode",
    subtitle: String? = null,
    showBackButton: Boolean = false,
    connectionStatus: ConnectionStatus = ConnectionStatus.DEMO,
    onBackClick: () -> Unit = {},
    onCommandPaletteClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    Surface(
        color = DevSurface,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("app_top_bar")
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Back button or Logo + Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (showBackButton) {
                        IconButton(
                            onClick = onBackClick,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("top_bar_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = DevTextPrimary
                            )
                        }
                    } else {
                        // App Brand Logo
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(DevSurfaceElevated)
                                .border(1.dp, DevBorder, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = "OpenCode Logo",
                                tint = DevAccentCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = title,
                                style = Typography.titleLarge,
                                color = DevTextPrimary,
                                maxLines = 1
                            )
                            if (!showBackButton) {
                                StatusPill(status = connectionStatus)
                            }
                        }
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = Typography.bodySmall,
                                color = DevTextSecondary,
                                maxLines = 1,
                                fontFamily = DevMonospace
                            )
                        }
                    }
                }

                // Right: Action buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    actions()

                    // Quick Command Palette trigger
                    IconButton(
                        onClick = onCommandPaletteClick,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("top_bar_command_palette_button")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(DevSurfaceElevated)
                                .border(1.dp, DevBorder, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = ">_",
                                fontFamily = DevMonospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = DevAccentCyan
                            )
                        }
                    }

                    // Global Search
                    IconButton(
                        onClick = onSearchClick,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("top_bar_search_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = DevTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Settings Shortcut (if not on back screens)
                    if (!showBackButton) {
                        IconButton(
                            onClick = onSettingsClick,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("top_bar_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = DevTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Subtle divider line
            HorizontalDivider(color = DevBorder, thickness = 1.dp)
        }
    }
}
