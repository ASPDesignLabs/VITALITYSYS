package com.snakesan.vitalitysys

import java.nio.ByteBuffer

// ... Enums remain the same ...

enum class AppMode { DASHBOARD, INTERRUPT_CAPTURE, INTERRUPT_ACTION, INTERRUPT_RESTORE, NUTRITION_CAPTURE }
enum class UploadState { IDLE, CONNECTING, UPLOADING, DOWNLOADING, SUCCESS_UPLOAD, SUCCESS_DOWNLOAD }

data class TelemetryEvent(val protocolId: Int, val timestamp: Long) {
    companion object {
        fun fromBytes(bytes: ByteArray): TelemetryEvent {
            val buffer = ByteBuffer.wrap(bytes)
            return TelemetryEvent(buffer.int, buffer.long)
        }
    }
}
