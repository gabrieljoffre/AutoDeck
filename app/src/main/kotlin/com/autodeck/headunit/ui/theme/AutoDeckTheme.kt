package com.autodeck.headunit.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object AutoDeckColors {
    val BgCarbon = Color(0xFF0A0D14)
    val SurfaceDock = Color(0xFF101520)
    val SurfaceCard = Color(0xFF161D2B)
    val SurfaceElevated = Color(0xFF1E2738)
    val BorderSubtle = Color(0xFF2B374D)

    val AccentCyan = Color(0xFF22D3EE)
    val AccentBlue = Color(0xFF3B82F6)
    val StatusGreen = Color(0xFF22C55E)
    val StatusAmber = Color(0xFFF59E0B)
    val StatusRed = Color(0xFFEF4444)

    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
}

private val DarkScheme = darkColorScheme(
    primary = AutoDeckColors.AccentCyan,
    onPrimary = Color(0xFF03151B),
    secondary = AutoDeckColors.AccentBlue,
    onSecondary = Color.White,
    background = AutoDeckColors.BgCarbon,
    onBackground = AutoDeckColors.TextPrimary,
    surface = AutoDeckColors.SurfaceCard,
    onSurface = AutoDeckColors.TextPrimary,
    surfaceVariant = AutoDeckColors.SurfaceElevated,
    onSurfaceVariant = AutoDeckColors.TextSecondary,
    error = AutoDeckColors.StatusRed,
    onError = Color.White,
    outline = AutoDeckColors.BorderSubtle
)

@Composable
fun AutoDeckTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        content = content
    )
}
