package com.snakesan.vitalitysys.debug

import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.snakesan.vitalitysys.MainActivity
import com.snakesan.vitalitysys.Protocol
import com.snakesan.vitalitysys.SentinelWorker
import com.snakesan.vitalitysys.vibrateAck

// All watch-side debug/testing tools live here, isolated from the real
// alert pipeline. forceRunSentinel checks DebugFlags.isEnabled() itself, so
// this stays inert even if something reaches it outside the normal UI hook
// (WatchComponents.kt's long-press).

// Simulated "bleed" damage from a manually-forced test alert. On the watch,
// real overdue penalties come straight out of VitalityMath — this map only
// ever gets populated by forceRunSentinel below, purely so a debug-forced
// alert can be watched decaying HP the way a real one does on the phone.
private val activeDebugBleeds = mutableMapOf<Int, Long>()

fun debugBleedDamage(): Int {
    var total = 0
    activeDebugBleeds.forEach { (_, startTime) ->
        val elapsedMins = (System.currentTimeMillis() - startTime) / 60000
        total += 25 + elapsedMins.toInt()
    }
    return total
}

fun clearDebugBleed(protocolId: Int) {
    activeDebugBleeds.remove(protocolId)
}

// Manually fires a fake overdue alert for the given protocol, instantly,
// so the alert/HP-damage flow can be tested without waiting for something
// to actually become due.
fun MainActivity.forceRunSentinel(debugProtocol: Protocol) {
    if (!DebugFlags.isEnabled(this)) return

    vibrateAck(this, heavy = true)

    activeDebugBleeds[debugProtocol.id] = System.currentTimeMillis()
    broadcastToOverseerLocal()

    val data = Data.Builder().putBoolean("IS_DEBUG", true).putInt("DEBUG_PROTO", debugProtocol.id).build()
    val workRequest = OneTimeWorkRequestBuilder<SentinelWorker>().setInputData(data).build()
    WorkManager.getInstance(this).enqueue(workRequest)
}
