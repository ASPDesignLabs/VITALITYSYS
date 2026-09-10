package com.snakesan.vitalitysys

import androidx.compose.ui.graphics.Color

// --- COLORS ---
val VitalityBg = Color(0xFF050505)
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
