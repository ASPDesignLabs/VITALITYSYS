package com.snakesan.vitalitysys

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// --- COLORS ---
val VitalityBg = Color(0xFF050505)
val NeonBg = VitalityBg
val Graphite = Color(0xFF121212)
val NeonCyan = Color(0xFF00F3FF)
val NeonPink = Color(0xFFFF0055)
val NeonGreen = Color(0xFF00FF41)
val NeonAmber = Color(0xFFFF9900)

// --- SHAPES ---
// Shared cut-corner motif, matching ACK's AckHelpShape recipe so both apps'
// panels/cards/buttons/dialogs read as the same design language.
val VitalityShape = CutCornerShape(
    topStart = 10.dp,
    topEnd = 2.dp,
    bottomStart = 2.dp,
    bottomEnd = 10.dp
)

// Larger single-corner cut used for primary/hero actions, matching ACK's
// HeroButton shape.
val VitalityHeroShape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp)

// --- TYPOGRAPHY ---
// ACK's "command console" identity is carried almost entirely by setting
// monospace everywhere. Rather than touching every individual Text() call,
// this swaps the whole MaterialTheme typography scale to FontFamily.Monospace
// once, here -- any Text() that doesn't explicitly override fontFamily
// inherits it automatically.
private val baseTypography = Typography()
val VitalityTypography = Typography(
    displayLarge = baseTypography.displayLarge.copy(fontFamily = FontFamily.Monospace),
    displayMedium = baseTypography.displayMedium.copy(fontFamily = FontFamily.Monospace),
    displaySmall = baseTypography.displaySmall.copy(fontFamily = FontFamily.Monospace),
    headlineLarge = baseTypography.headlineLarge.copy(fontFamily = FontFamily.Monospace),
    headlineMedium = baseTypography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
    headlineSmall = baseTypography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
    titleLarge = baseTypography.titleLarge.copy(fontFamily = FontFamily.Monospace),
    titleMedium = baseTypography.titleMedium.copy(fontFamily = FontFamily.Monospace),
    titleSmall = baseTypography.titleSmall.copy(fontFamily = FontFamily.Monospace),
    bodyLarge = baseTypography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
    bodyMedium = baseTypography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    bodySmall = baseTypography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    labelLarge = baseTypography.labelLarge.copy(fontFamily = FontFamily.Monospace),
    labelMedium = baseTypography.labelMedium.copy(fontFamily = FontFamily.Monospace),
    labelSmall = baseTypography.labelSmall.copy(fontFamily = FontFamily.Monospace)
)

// --- ENUMS ---
enum class Protocol(val id: Int, val label: String, val colorHex: Long) {
    NUTRIENT(0, "NUTRIENT", 0xFF00F3FF),
    CHEMISTRY(1, "CHEMISTRY", 0xFFFF0055),
    HYDRATION(2, "HYDRATION", 0xFF00FF41),
    MAINTENANCE(3, "MAINTENANCE", 0xFFFF9900)
}

enum class SyncState { HIDDEN, CONNECTING_RX, RECEIVING, CONNECTING_TX, SENDING, SUCCESS }

@Composable
fun NeonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NeonCyan,
            secondary = NeonPink,
            tertiary = NeonGreen,
            background = NeonBg,
            surface = NeonBg
        ),
        typography = VitalityTypography,
        content = content
    )
}
