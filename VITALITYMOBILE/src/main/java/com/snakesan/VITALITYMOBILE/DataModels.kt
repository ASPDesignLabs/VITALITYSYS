package com.snakesan.vitalitysys

import java.nio.ByteBuffer

// ... Enums remain the same ...

enum class AppMode { DASHBOARD, INTERRUPT_CAPTURE, INTERRUPT_ACTION, INTERRUPT_RESTORE, NUTRITION_CAPTURE }
enum class UploadState { IDLE, CONNECTING, UPLOADING, DOWNLOADING, SUCCESS_UPLOAD, SUCCESS_DOWNLOAD }

// How hard it is to back out of the interrupt-capture/action screens without
// completing the protocol. Lets someone dial this tool between "just a
// reminder, don't fight me" and "no, actually make me do the thing" —
// steering their own behavior on purpose rather than the app deciding for
// them.
enum class EscapeDifficulty(val id: Int, val label: String, val description: String) {
    EASY(0, "EASY", "Back exits immediately. Good when this is just a reminder."),
    STANDARD(1, "STANDARD", "Back asks you to confirm skipping first."),
    FIRM(2, "FIRM", "Back does nothing. Finish or explicitly abandon it.");

    companion object {
        fun fromId(id: Int): EscapeDifficulty = values().firstOrNull { it.id == id } ?: STANDARD
    }
}

// itemKey identifies which specific dose/hygiene task this event is about
// (see SysConfig.doseKey / hygieneKey). Empty for NUTRIENT/HYDRATION, which
// have no sub-items.
data class TelemetryEvent(val protocolId: Int, val timestamp: Long, val itemKey: String = "") {
    companion object {
        fun fromBytes(bytes: ByteArray): TelemetryEvent {
            val buffer = ByteBuffer.wrap(bytes)
            val protocolId = buffer.int
            val timestamp = buffer.long
            val itemKey = if (buffer.remaining() >= 4) {
                val keyLen = buffer.int
                val keyBytes = ByteArray(keyLen)
                buffer.get(keyBytes)
                String(keyBytes, Charsets.UTF_8)
            } else ""
            return TelemetryEvent(protocolId, timestamp, itemKey)
        }
    }
}

// AlertPayload carries which specific dose/hygiene-task key (see
// SysConfig.doseKey / hygieneKey) an alert is about, plus a human-readable
// message, so a watch-triggered phone alert/notification can name the
// specific reminder and mark the right item complete when confirmed.
// itemKey is "" for NUTRIENT/HYDRATION, which have no sub-items.
data class AlertPayload(val protocolId: Int, val itemKey: String, val message: String) {
    companion object {
        fun fromBytes(bytes: ByteArray): AlertPayload {
            val buffer = ByteBuffer.wrap(bytes)
            val protocolId = buffer.int
            fun readString(): String {
                if (buffer.remaining() < 4) return ""
                val len = buffer.int
                val strBytes = ByteArray(len)
                buffer.get(strBytes)
                return String(strBytes, Charsets.UTF_8)
            }
            val itemKey = readString()
            val message = readString()
            return AlertPayload(protocolId, itemKey, message)
        }
    }
}

fun encodeKeySet(keys: Set<String>): String = keys.joinToString(",")
fun decodeKeySet(raw: String): Set<String> = if (raw.isBlank()) emptySet() else raw.split(",").toSet()
