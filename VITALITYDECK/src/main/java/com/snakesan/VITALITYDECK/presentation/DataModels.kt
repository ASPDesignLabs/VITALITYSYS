package com.snakesan.vitalitysys

import java.nio.ByteBuffer

data class TelemetryEvent(val protocolId: Int, val timestamp: Long) {
    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(12)
        buffer.putInt(protocolId)
        buffer.putLong(timestamp)
        return buffer.array()
    }
}


