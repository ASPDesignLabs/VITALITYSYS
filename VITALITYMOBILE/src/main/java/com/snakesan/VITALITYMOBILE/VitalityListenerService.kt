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
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.snakesan.vitalitysys.data.NotificationAudit
import com.snakesan.vitalitysys.data.VitalityDatabase
import com.snakesan.vitalitysys.receivers.NotificationDismissReceiver
import com.snakesan.vitalitysys.workers.AuditCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class VitalityListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        super.onMessageReceived(event)

        // Handle incoming alerts from Watch to trigger Phone notifications
        if (event.path == "/sys/alert_phone") {
            val protoId = ByteBuffer.wrap(event.data).int
            val protocol = Protocol.values().firstOrNull { it.id == protoId }

            if (protocol != null) {
                createNotificationChannel()
                triggerNotification(protocol)
            }
        }
    }

    private fun triggerNotification(protocol: Protocol) {
        val remoteInput = RemoteInput.Builder("KEY_TEXT_REPLY")
            .setLabel("State Current Vector...")
            .build()

        val replyIntent = Intent(this, NotificationReplyReceiver::class.java).apply {
            putExtra("PROTOCOL_ID", protocol.id)
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
        }
        val appPendingIntent = PendingIntent.getActivity(
            this, protocol.id, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // --- START OF NOTIFICATION BUILDER ---
        val builder = NotificationCompat.Builder(this, "vitality_urgent")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("PROTOCOL: ${protocol.label}")
            .setContentText("Anomaly detected. Please state current vector.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setColor(0xFF00F3FF.toInt())
            .setFullScreenIntent(appPendingIntent, true)
            .addAction(replyAction)
            .setAutoCancel(true)

        // --- NEW CUSTOM URL LOGIC FOR CHEMISTRY ---
        if (protocol == Protocol.CHEMISTRY) {
            val urlIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com"))
            val urlPendingIntent = PendingIntent.getActivity(
                this,
                protocol.id + 100, // Offset ID so it doesn't conflict with appPendingIntent
                urlIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val urlAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_info_details,
                "EXTERNAL LINK", // Text on the notification button
                urlPendingIntent
            ).build()

            builder.addAction(urlAction)
        }

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