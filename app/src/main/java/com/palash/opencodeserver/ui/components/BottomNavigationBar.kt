package com.palash.opencodeserver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.palash.opencodeserver.ui.navigation.NavigationTab
import com.palash.opencodeserver.ui.theme.*

@Composable
fun AppBottomNavigation(
    selectedTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = DevSurface,
        modifier = modifier
            .fillMaxWidth()
            .testTag("app_bottom_navigation")
    ) {
        Column {
            HorizontalDivider(color = DevBorder, thickness = 1.dp)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .height(60.dp)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavigationTab.values().forEach { tab ->
                    val isSelected = tab == selectedTab
                    val icon = getTabIcon(tab, isSelected)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true, color = DevAccentCyan)
                            ) {
                                onTabSelected(tab)
                            }
                            .testTag("nav_tab_${tab.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isSelected) DevAccentCyanSubtle else androidx.compose.ui.graphics.Color.Transparent
                                    )
                                    .padding(horizontal = 14.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = tab.label,
                                    tint = if (isSelected) DevAccentCyan else DevTextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = tab.label,
                                fontSize = 10.sp,
                                color = if (isSelected) DevAccentCyan else DevTextMuted,
                                style = Typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun getTabIcon(tab: NavigationTab, isSelected: Boolean): ImageVector {
    return when (tab) {
        NavigationTab.SERVER -> if (isSelected) Icons.Filled.Dns else Icons.Outlined.Dns
        NavigationTab.LOGS -> if (isSelected) Icons.Filled.Article else Icons.Outlined.Article
        NavigationTab.TERMINAL -> if (isSelected) Icons.Filled.Terminal else Icons.Outlined.Terminal
        NavigationTab.ENDPOINTS -> if (isSelected) Icons.Filled.Api else Icons.Outlined.Api
        NavigationTab.CONFIG -> if (isSelected) Icons.Filled.Tune else Icons.Outlined.Tune
    }
}
