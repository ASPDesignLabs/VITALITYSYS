package com.snakesan.vitalitysys

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.snakesan.vitalitysys.data.NotificationAudit
import com.snakesan.vitalitysys.data.VitalityDatabase
import com.snakesan.vitalitysys.receivers.NotificationDismissReceiver
import com.snakesan.vitalitysys.workers.AuditCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class VitalityListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        super.onMessageReceived(event)

        // Handle incoming alerts from Watch to trigger Phone notifications
        if (event.path == "/sys/alert_phone") {
            val alert = AlertPayload.fromBytes(event.data)
            val protocol = Protocol.values().firstOrNull { it.id == alert.protocolId }

            if (protocol != null) {
                createNotificationChannel()
                triggerNotification(protocol, alert.itemKey, alert.message)
            } else if (alert.protocolId == PAIN_PROTOCOL_ID) {
                createNotificationChannel()
                triggerPainCheckNotification(alert.message)
            }
        }

        // Pain logs and task completions from the watch. MainActivity handles
        // these itself while it's running; these are the fallback for when it
        // isn't (see WatchMessages for why and how). A malformed payload must
        // never take the app down from a background callback.
        if (event.path == "/sys/pain_log") {
            runCatching { PainLogEvent.fromBytes(event.data) }.getOrNull()
                ?.let { WatchMessages.handlePainWhenClosed(this, it) }
        }

        if (event.path == "/sys/telemetry") {
            runCatching { TelemetryEvent.fromBytes(event.data) }.getOrNull()
                ?.let { WatchMessages.handleTelemetryWhenClosed(this, it) }
        }
    }

    // The watch's authoritative counts + completed dose/task keys. Applied by
    // MainActivity while it's running; written straight to the database here
    // when it isn't, so the phone (and HeartbeatWorker's HP maths) doesn't go
    // on using stale numbers.
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == "/vitality_state") {
                // Values must be read before this callback returns — the
                // buffer is released after it.
                val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                if (map.containsKey("nutrients") && map.containsKey("hydration")) {
                    WatchMessages.applyStateWhenClosed(
                        this,
                        nutrients = map.getInt("nutrients"),
                        hydration = map.getInt("hydration"),
                        completedKeys = map.getString("completedKeys") ?: "",
                        watchDayId = if (map.containsKey("dayId")) map.getInt("dayId") else null
                    )
                }
            }
        }
    }

    // Start the audit trail for an alert: a NotificationAudit row, plus a
    // 5-minute AuditCheckWorker that flags it IGNORED if nothing ever
    // responds (see fulfillProtocolAudit / NotificationReplyReceiver /
    // NotificationDismissReceiver for the other ends of this trail).
    private fun startAuditTrail(notificationId: Int, protocolType: String, itemKey: String) {
        val issuedAt = System.currentTimeMillis()

        CoroutineScope(Dispatchers.IO).launch {
            VitalityDatabase.getDatabase(applicationContext).systemDao().insertAudit(
                NotificationAudit(
                    notificationId = notificationId,
                    protocolType = protocolType,
                    timestampIssued = issuedAt,
                    itemKey = itemKey
                )
            )
        }

        val auditCheckWork = OneTimeWorkRequestBuilder<AuditCheckWorker>()
            .setInitialDelay(5, TimeUnit.MINUTES)
            .setInputData(workDataOf("NOTIF_ID" to notificationId))
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "AuditCheck_$notificationId", ExistingWorkPolicy.REPLACE, auditCheckWork
        )
    }

    // The pain follow-up check-in. Not a Protocol: it is audited as its own
    // PAIN_CHECK item (so it no longer drags down the CHEMISTRY grade), it
    // closes when pain is logged (see MainActivity.markPainCheckDone), and it
    // causes no HP bleed (MainActivity only applies those for real
    // protocols). An ordinary notification — not the full-screen takeover real
    // protocol alerts use, which used to show a fake medication screen here —
    // and tapping it opens the pain picker.
    private fun triggerPainCheckNotification(message: String) {
        startAuditTrail(PAIN_PROTOCOL_ID, PAIN_CHECK_TYPE, "")

        // REORDER_TO_FRONT rather than CLEAR_TASK: don't destroy a response
        // the user is part-way through just to show this.
        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra("OPEN_PAIN_PICKER", true)
        }
        val appPendingIntent = PendingIntent.getActivity(
            this, PAIN_PROTOCOL_ID, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(this, NotificationDismissReceiver::class.java).apply {
            putExtra("NOTIF_ID", PAIN_PROTOCOL_ID)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            this, PAIN_PROTOCOL_ID + 200, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, "vitality_urgent")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("PAIN CHECK-IN")
            .setContentText(message.ifEmpty { "How is it now? Log your pain level." })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFFFF0055.toInt())
            .setContentIntent(appPendingIntent)
            .setDeleteIntent(dismissPendingIntent)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(this).notify(PAIN_PROTOCOL_ID, builder.build())
        } catch (e: SecurityException) { }
    }

    private fun triggerNotification(protocol: Protocol, itemKey: String, message: String) {
        startAuditTrail(protocol.id, protocol.name, itemKey)

        val remoteInput = RemoteInput.Builder("KEY_TEXT_REPLY")
            .setLabel("State Current Vector...")
            .build()

        val replyIntent = Intent(this, NotificationReplyReceiver::class.java).apply {
            putExtra("PROTOCOL_ID", protocol.id)
            putExtra("ITEM_KEY", itemKey)
        }

        val replyPendingIntent = PendingIntent.getBroadcast(
            this, protocol.id, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "LOG VECTOR", replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("PROTOCOL_ID", protocol.id)
            putExtra("ITEM_KEY", itemKey)
        }
        val appPendingIntent = PendingIntent.getActivity(
            this, protocol.id, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Swiping the notification away (rather than tapping it or replying)
        // is itself an audit-relevant outcome — mark it DISMISSED/ABANDONED.
        val dismissIntent = Intent(this, NotificationDismissReceiver::class.java).apply {
            putExtra("NOTIF_ID", protocol.id)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            this, protocol.id + 200, dismissIntent, // Offset ID so it doesn't conflict with the other PendingIntents above
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // --- START OF NOTIFICATION BUILDER ---
        val builder = NotificationCompat.Builder(this, "vitality_urgent")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("PROTOCOL: ${protocol.label}")
            .setContentText(message.ifEmpty { "Anomaly detected. Please state current vector." })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setColor(0xFF00F3FF.toInt())
            .setFullScreenIntent(appPendingIntent, true)
            .addAction(replyAction)
            .setDeleteIntent(dismissPendingIntent)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(this).notify(protocol.id, builder.build())
        } catch (e: SecurityException) { }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "vitality_urgent", "Vitality Protocols", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Interrupts for bio-maintenance"
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}