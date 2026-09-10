package com.snakesan.vitalitysys

import java.nio.ByteBuffer

data class SysConfig(
    val meal1Time: Float,
    val meal2Time: Float,
    val meal3Time: Float,
    val medsWeekday: Float,
    val medsWeekend: Float,
    val hydrationTargetMl: Float,
    val activeStartHour: Float,
    val activeEndHour: Float,
    val maint1Time: Float,
    val maint2Time: Float,
    // Ensure this is here
    val clinicalOverride: Float = 0f
) {
    companion object {
        val DEFAULT = SysConfig(540f, 780f, 1140f, 480f, 600f, 3250f, 8f, 22f, 450f, 1320f, 0f)

        fun fromBytes(bytes: ByteArray): SysConfig {
            if (bytes.isEmpty()) return DEFAULT
            val buffer = ByteBuffer.wrap(bytes)
            return SysConfig(
                buffer.int.toFloat(), buffer.int.toFloat(), buffer.int.toFloat(),
                buffer.int.toFloat(), buffer.int.toFloat(),
                buffer.int.toFloat(), buffer.int.toFloat(), buffer.int.toFloat(),
                buffer.int.toFloat(), buffer.int.toFloat(),
                if (buffer.remaining() >= 4) buffer.int.toFloat() else 0f
            )
        }
    }

    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(44)
        buffer.putInt(meal1Time.toInt())
        buffer.putInt(meal2Time.toInt())
        buffer.putInt(meal3Time.toInt())
        buffer.putInt(medsWeekday.toInt())
        buffer.putInt(medsWeekend.toInt())
        buffer.putInt(hydrationTargetMl.toInt())
        buffer.putInt(activeStartHour.toInt())
        buffer.putInt(activeEndHour.toInt())
        buffer.putInt(maint1Time.toInt())
        buffer.putInt(maint2Time.toInt())
        buffer.putInt(clinicalOverride.toInt())
        return buffer.array()
    }
}