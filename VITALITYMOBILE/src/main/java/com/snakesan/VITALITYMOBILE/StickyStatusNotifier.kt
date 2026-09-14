package com.snakesan.vitalitysys

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

// The persistent "you're still mid-response" notification. Posted once the
// user actually opens the app off an alert (see MainActivity.handleIntent),
// updated as their captured context/photo firms up, and cleared only by the
// shared completion path (MainActivity.fulfillProtocolAudit /
// markCompletedExternally) — never by backing out of the capture/action
// screens. The point is a nag that survives getting distracted mid-flow and
// only goes away once the underlying protocol is actually done, regardless
// of which device (phone or watch) finished it.
object StickyStatusNotifier {
    private const val CHANNEL_ID = "vitality_in_progress"

    // +500 keeps this clear of the alert notification ids (protocol.id,
    // 0-3) and the dismiss-receiver PendingIntent request codes (+200).
    private fun notificationId(protocolId: Int) = protocolId + 500

    fun notifyInProgress(
        context: Context,
        protocol: Protocol,
        itemKey: String,
        userContext: String,
        resumeMode: String,
        photoUri: String?
    ) {
        createChannelIfNeeded(context)

        val resumeIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("PROTOCOL_ID", protocol.id)
            putExtra("ITEM_KEY", itemKey)
            putExtra("RESUME_MODE", resumeMode)
            putExtra("RESUME_CONTEXT", userContext)
            photoUri?.let { putExtra("RESUME_PHOTO_URI", it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId(protocol.id), resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = if (userContext.isNotBlank()) {
            "Interrupted: ${userContext.uppercase()}"
        } else {
            "Tap to finish responding to ${protocol.label}"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("IN PROGRESS: ${protocol.label}")
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)

        try {
            NotificationManagerCompat.from(context).notify(notificationId(protocol.id), builder.build())
        } catch (e: SecurityException) { /* notifications permission not granted */ }
    }

    fun clear(context: Context, protocolId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId(protocolId))
    }

    private fun createChannelIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Vitality In-Progress Status", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Stays up while you're mid-response to a protocol alert"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
