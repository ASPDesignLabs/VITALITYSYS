package com.snakesan.vitalitysys

import java.nio.ByteBuffer

// itemKey identifies which specific dose/hygiene task this event is about
// (see SysConfig.doseKey / hygieneKey). Empty for NUTRIENT/HYDRATION, which
// have no sub-items.
data class TelemetryEvent(val protocolId: Int, val timestamp: Long, val itemKey: String = "") {
    fun toBytes(): ByteArray {
        val keyBytes = itemKey.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + 8 + 4 + keyBytes.size)
        buffer.putInt(protocolId)
        buffer.putLong(timestamp)
        buffer.putInt(keyBytes.size)
        buffer.put(keyBytes)
        return buffer.array()
    }
}

// AlertPayload carries which specific dose/hygiene-task key (see
// SysConfig.doseKey / hygieneKey) an alert is about, plus a human-readable
// message, so a watch-triggered phone alert/notification can name the
// specific reminder and mark the right item complete when confirmed.
// itemKey is "" for NUTRIENT/HYDRATION, which have no sub-items.
data class AlertPayload(val protocolId: Int, val itemKey: String, val message: String) {
    fun toBytes(): ByteArray {
        val itemKeyBytes = itemKey.toByteArray(Charsets.UTF_8)
        val msgBytes = message.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + 4 + itemKeyBytes.size + 4 + msgBytes.size)
        buffer.putInt(protocolId)
        buffer.putInt(itemKeyBytes.size)
        buffer.put(itemKeyBytes)
        buffer.putInt(msgBytes.size)
        buffer.put(msgBytes)
        return buffer.array()
    }
}

fun encodeKeySet(keys: Set<String>): String = keys.joinToString(",")
fun decodeKeySet(raw: String): Set<String> = if (raw.isBlank()) emptySet() else raw.split(",").toSet()
