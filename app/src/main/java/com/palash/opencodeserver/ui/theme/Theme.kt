package com.palash.opencodeserver.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val OpenCodeDarkColorScheme = darkColorScheme(
    primary = DevAccentCyan,
    onPrimary = Color(0xFF031626),
    primaryContainer = Color(0xFF0C3854),
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = DevAccentEmerald,
    onSecondary = Color(0xFF022013),
    secondaryContainer = Color(0xFF064E3B),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = DevAccentAmber,
    onTertiary = Color(0xFF261A00),
    background = DevBackground,
    onBackground = DevTextPrimary,
    surface = DevSurface,
    onSurface = DevTextPrimary,
    surfaceVariant = DevSurfaceElevated,
    onSurfaceVariant = DevTextSecondary,
    outline = DevBorder,
    outlineVariant = DevBorderLight
)

val OpenCodeLightColorScheme = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0369A1),
    secondary = Color(0xFF059669),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF047857),
    tertiary = Color(0xFFD97706),
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceElevated,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    outlineVariant = Color(0xFFE5E7EB)
)

@Composable
fun OpenCodeTheme(
    darkTheme: Boolean = true, // Default to dark developer mode
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) OpenCodeDarkColorScheme else OpenCodeLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
