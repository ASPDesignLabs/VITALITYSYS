package com.snakesan.vitalitysys

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.snakesan.vitalitysys.data.SystemLog
import com.snakesan.vitalitysys.data.VitalityDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class NotificationReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInput?.getCharSequence("KEY_TEXT_REPLY")?.toString() ?: "UNKNOWN VECTOR"
        val protocolId = intent.getIntExtra("PROTOCOL_ID", -1)
        val itemKey = intent.getStringExtra("ITEM_KEY") ?: ""
        val protocol = Protocol.values().firstOrNull { it.id == protocolId } ?: return
        val now = System.currentTimeMillis()

        // Audit fulfillment + cancelling the alert/sticky-in-progress
        // notifications + reconciling any in-flight overlay for this exact
        // protocol all go through the same shared path a watch-driven
        // completion uses, so a quick-reply from the lock screen is treated
        // as a real response no matter what else might be going on (e.g. the
        // app already open and mid-capture for the same alert).
        MainActivity.markCompletedExternally(context, protocol, itemKey, "CLICKED")

        CoroutineScope(Dispatchers.IO).launch {
            val db = VitalityDatabase.getDatabase(context)

            db.systemDao().insertLog(
                SystemLog(timestamp = now, type = protocol.name, value = 1, note = replyText)
            )

            val c = Calendar.getInstance()
            val todayId = (c.get(Calendar.YEAR) * 1000) + c.get(Calendar.DAY_OF_YEAR)
            val stats = db.systemDao().getDailyStats(todayId)
            if (stats != null) {
                val newStats = when (protocol) {
                    Protocol.NUTRIENT -> stats.copy(nutrientCount = stats.nutrientCount + 1)
                    Protocol.HYDRATION -> stats.copy(hydrationCount = stats.hydrationCount + 1)
                    Protocol.CHEMISTRY, Protocol.MAINTENANCE -> if (itemKey.isNotEmpty()) {
                        stats.copy(completedKeys = encodeKeySet(decodeKeySet(stats.completedKeys) + itemKey))
                    } else stats
                }
                db.systemDao().setDailyStats(newStats)

                // Push to Watch
                val putDataReq = PutDataMapRequest.create("/vitality_state_from_phone").apply {
                    dataMap.putInt("nutrients", newStats.nutrientCount)
                    dataMap.putInt("hydration", newStats.hydrationCount)
                    dataMap.putString("completedKeys", newStats.completedKeys)
                    dataMap.putLong("timestamp", now)
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(context).putDataItem(putDataReq)
            }
        }
    }
}
