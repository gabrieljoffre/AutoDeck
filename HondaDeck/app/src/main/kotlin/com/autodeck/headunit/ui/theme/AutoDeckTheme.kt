package com.autodeck.headunit.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object AutoDeckColors {
    val BgCarbon = Color(0xFF040911)
    val HondaDeepBg = Color(0xFF07111D)
    val SurfaceDock = Color(0xFF0B1522)
    val SurfaceCard = Color(0xFF0E1A29)
    val HondaCardHeader = Color(0xFF132234)
    val SurfaceElevated = Color(0xFF17273B)
    val BorderSubtle = Color(0xFF213C52)

    val AccentCyan = Color(0xFF29E7FF)
    val HondaCyanSoft = Color(0xFF67C8DF)
    val AccentBlue = Color(0xFF2F88FF)
    val StatusGreen = Color(0xFF1FE676)
    val StatusAmber = Color(0xFFFFD426)
    val StatusRed = Color(0xFFFF2E4D)
    val FuelOrange = Color(0xFFFF6A2B)

    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF9AB2C9)
    val TextMuted = Color(0xFF607B94)
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
