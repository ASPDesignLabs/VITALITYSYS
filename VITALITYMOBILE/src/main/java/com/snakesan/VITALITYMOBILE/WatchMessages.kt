package com.snakesan.vitalitysys

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.snakesan.vitalitysys.data.DailyStats
import com.snakesan.vitalitysys.data.VitalityDatabase
import com.snakesan.vitalitysys.data.savePainLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

// What the phone does with messages from the watch when MainActivity ISN'T
// running. While it is, MainActivity's own live listeners handle these
// (see MainActivity.onMessageReceived / onDataChanged) and
// VitalityListenerService stays out of the way, so each message is handled
// exactly once either way.
//
// Why this exists: the watch drops an entry from its retry queue as soon as
// sendMessage succeeds, which only means the message reached the phone — not
// that anything handled it. With the app swiped away and nothing declared in
// the manifest for these paths, a pain log was silently lost and a finished
// task never closed its audit row (so it later graded as missed).
object WatchMessages {
    private const val CHANNEL_ID = "vitality_watch_logged"

    // Clear of the alert ids (0-3), the dismiss-receiver request codes (+200)
    // and the sticky in-progress ids (+500).
    private const val PAIN_NOTIFICATION_ID = 700
    private const val MEAL_NOTIFICATION_ID = 701

    // ---- Pain ---------------------------------------------------------------

    // Saves the pain level right away, then posts a quiet "tap to add details"
    // notification. Used by the background fallback AND by the live handler:
    // even with the app running, Android can refuse to open an Activity from
    // the background, and the level must not depend on that screen opening.
    // The row is keyed on the watch's timestamp (see savePainLog), so the
    // screen that opens later for the same event reuses it.
    fun recordPain(context: Context, event: PainLogEvent) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            VitalityDatabase.getDatabase(app).systemDao().savePainLog(event.level, event.loggedAt)
            val intent = launchIntent(app).apply {
                putExtra("PROTOCOL_ID", 99)
                putExtra("PAIN_LEVEL", event.level)
                putExtra("PAIN_TS", event.loggedAt)
            }
            post(
                app, PAIN_NOTIFICATION_ID, intent,
                title = if (event.level == 0) "PAIN LOGGED: RESOLVED (0/10)" else "PAIN LOGGED: ${event.level}/10",
                text = "Saved. Tap to add details (optional)."
            )
        }
    }

    // Background fallback entry point (VitalityListenerService).
    fun handlePainWhenClosed(context: Context, event: PainLogEvent) {
        if (MainActivity.isRunning()) return
        recordPain(context, event)
    }

    fun cancelPainNotification(context: Context) =
        NotificationManagerCompat.from(context).cancel(PAIN_NOTIFICATION_ID)

    // ---- Task completions ---------------------------------------------------

    // The background twin of MainActivity's live /sys/telemetry branch: closes
    // the audit row and clears the alert + sticky notifications for what the
    // watch just finished. (Counts and completed keys arrive separately,
    // authoritatively, through the /vitality_state sync — see applyStateWhenClosed.)
    fun handleTelemetryWhenClosed(context: Context, telemetry: TelemetryEvent) {
        if (MainActivity.isRunning()) return
        val app = context.applicationContext
        val protocol = Protocol.values().firstOrNull { it.id == telemetry.protocolId } ?: return

        if (protocol == Protocol.NUTRIENT) {
            MainActivity.markCompletedExternally(app, protocol, "", "WATCH_LOGGED")
            notifyMealLogged(app)
            return
        }
        if (protocol == Protocol.HYDRATION) {
            CoroutineScope(Dispatchers.IO).launch { HealthConnectManager(app).logWater(250.0) }
        }
        MainActivity.markCompletedExternally(app, protocol, telemetry.itemKey, "WATCH_LOGGED")
    }

    // The watch already counted the meal; only the macro detail (which only
    // the phone can enter) is left, and the phone can't force its screen open
    // from the background — so offer it as a quiet notification instead.
    // Used by the live handler too, for the same "launch may be refused" reason.
    fun notifyMealLogged(context: Context) {
        val app = context.applicationContext
        val intent = launchIntent(app).apply {
            putExtra("PROTOCOL_ID", Protocol.NUTRIENT.id)
            putExtra("LOGGED_VIA_WATCH", true)
        }
        post(
            app, MEAL_NOTIFICATION_ID, intent,
            title = "MEAL LOGGED ON WATCH",
            text = "Saved. Tap to add macros (optional)."
        )
    }

    fun cancelMealNotification(context: Context) =
        NotificationManagerCompat.from(context).cancel(MEAL_NOTIFICATION_ID)

    // ---- Counts / completed keys -------------------------------------------

    // The watch's /vitality_state sync carries today's counts and completed
    // dose/task keys (MainActivity.onDataChanged applies it live). Without the
    // app running, nothing wrote it to the database, so the phone — and
    // HeartbeatWorker's HP maths — kept using stale numbers.
    fun applyStateWhenClosed(
        context: Context, nutrients: Int, hydration: Int, completedKeys: String, watchDayId: Int?
    ) {
        if (MainActivity.isRunning()) return
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val dao = VitalityDatabase.getDatabase(app).systemDao()
            val dayId = todayId()
            val merged = mergeWatchState(
                dao.getDailyStats(dayId), dayId, nutrients, hydration, completedKeys,
                System.currentTimeMillis(), watchDayId
            ) ?: return@launch
            dao.setDailyStats(merged)
        }
    }

    // MERGES the watch's numbers into what the phone already has, rather than
    // overwriting it. With the app closed, the phone can still complete things
    // itself — the notification's quick-reply writes straight to the database —
    // and the watch never hears about those (nothing reads the phone's
    // /vitality_state_from_phone), so its next push still carries the old
    // count. Overwriting would silently undo the phone-side completion. Within
    // a day counts only go up and completed keys are only added, so taking the
    // larger count and the union of keys keeps both sides' completions, and is
    // safe to repeat. Returns null when nothing would change, because the
    // watch re-pushes every minute while its app is on screen.
    //
    // watchDayId is the day the watch stamped on its push. Merging can only
    // ever raise a number, so a push still carrying YESTERDAY's counts (the
    // watch used to reset only on open and in a 15-minute worker, so an app
    // left on screen across midnight kept pushing them) would stick for the
    // whole day if it were merged — it is ignored instead. null = an older
    // watch build that doesn't stamp its pushes: merged as before.
    internal fun mergeWatchState(
        existing: DailyStats?,
        dayId: Int,
        nutrients: Int,
        hydration: Int,
        completedKeys: String,
        now: Long,
        watchDayId: Int? = null
    ): DailyStats? {
        if (watchDayId != null && watchDayId != dayId) return null
        val mergedNutrients = maxOf(existing?.nutrientCount ?: 0, nutrients)
        val mergedHydration = maxOf(existing?.hydrationCount ?: 0, hydration)
        val existingKeys = existing?.let { decodeKeySet(it.completedKeys) } ?: emptySet()
        val mergedKeys = existingKeys + decodeKeySet(completedKeys)

        if (existing != null &&
            existing.nutrientCount == mergedNutrients &&
            existing.hydrationCount == mergedHydration &&
            existingKeys == mergedKeys
        ) return null

        return DailyStats(
            dayId = dayId,
            nutrientCount = mergedNutrients,
            hydrationCount = mergedHydration,
            completedKeys = encodeKeySet(mergedKeys),
            lastUpdated = now
        )
    }

    // ---- Shared plumbing ----------------------------------------------------

    private fun todayId(): Int {
        val c = Calendar.getInstance()
        return (c.get(Calendar.YEAR) * 1000) + c.get(Calendar.DAY_OF_YEAR)
    }

    // Brings an already-open app forward (or starts it), landing in onNewIntent
    // / onCreate -> handleIntent, the same entry the live message handler uses.
    private fun launchIntent(context: Context) = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }

    private fun post(context: Context, id: Int, intent: Intent, title: String, text: String) {
        createChannelIfNeeded(context)
        val pending = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pending)
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) { /* notifications permission not granted */ }
    }

    private fun createChannelIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Vitality Watch Logs", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Confirms something you logged on the watch was saved, and offers optional details"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
