package com.snakesan.vitalitysys

import android.app.NotificationManager
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

        if (protocolId != -1) {
            val protocol = Protocol.values().firstOrNull { it.id == protocolId }
            val now = System.currentTimeMillis()

            CoroutineScope(Dispatchers.IO).launch {
                val db = VitalityDatabase.getDatabase(context)

                // 1. Audit Update (Fulfillment Clock)
                val audit = db.systemDao().getAuditByNotificationId(protocolId)
                if (audit != null) {
                    val timeDelta = (now - audit.timestampIssued) / 1000 // in seconds
                    val finalStat = if (timeDelta > 3600) "CRITICAL_DELAY" else "SUCCESS"

                    db.systemDao().updateAudit(
                        audit.copy(
                            timestampInteracted = now,
                            timestampFulfilled = now,
                            interactionType = "CLICKED",
                            finalStatus = finalStat
                        )
                    )
                }

                // 2. Log Context
                db.systemDao().insertLog(
                    SystemLog(
                        timestamp = now,
                        type = protocol?.name ?: "UNKNOWN",
                        value = 1,
                        note = replyText
                    )
                )

                // 3. Update Stats
                val c = Calendar.getInstance()
                val todayId = (c.get(Calendar.YEAR) * 1000) + c.get(Calendar.DAY_OF_YEAR)
                val stats = db.systemDao().getDailyStats(todayId)
                if (stats != null && protocol != null) {
                    val newStats = when (protocol) {
                        Protocol.NUTRIENT -> stats.copy(nutrientCount = stats.nutrientCount + 1)
                        Protocol.HYDRATION -> stats.copy(hydrationCount = stats.hydrationCount + 1)
                        Protocol.CHEMISTRY, Protocol.MAINTENANCE -> if (itemKey.isNotEmpty()) {
                            stats.copy(completedKeys = encodeKeySet(decodeKeySet(stats.completedKeys) + itemKey))
                        } else stats
                    }
                    db.systemDao().setDailyStats(newStats)

                    // 4. Push to Watch
                    val putDataReq = PutDataMapRequest.create("/vitality_state_from_phone").apply {
                        dataMap.putInt("nutrients", newStats.nutrientCount)
                        dataMap.putInt("hydration", newStats.hydrationCount)
                        dataMap.putString("completedKeys", newStats.completedKeys)
                        dataMap.putLong("timestamp", now)
                    }.asPutDataRequest().setUrgent()
                    Wearable.getDataClient(context).putDataItem(putDataReq)
                }
            }

            // 5. Dismiss
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(protocolId)
        }
    }
}