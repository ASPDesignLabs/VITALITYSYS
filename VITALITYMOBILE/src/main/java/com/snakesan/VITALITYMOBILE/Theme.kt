package com.snakesan.vitalitysys

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// --- COLORS ---
val VitalityBg = Color(0xFF050505)
val NeonBg = VitalityBg
val NeonCyan = Color(0xFF00F3FF)
val NeonPink = Color(0xFFFF0055)
val NeonGreen = Color(0xFF00FF41)
val NeonAmber = Color(0xFFFF9900)

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
        content = content
    )
}
