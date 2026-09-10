package com.snakesan.vitalitysys

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.snakesan.vitalitysys.debug.DebugFlags
import java.util.Calendar

class SentinelWorker(val context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    private val LOOKAHEAD_MINUTES = 20

    // Flavor text for meal reminders, indexed by meal slot (up to the 5-meal cap).
    private val MEAL_MESSAGES = listOf(
        "Fuel cells empty. Intake imminent.",
        "Systems flagging. Refuel required.",
        "Running on fumes? Eat.",
        "Extended fasting detected. Refuel.",
        "Energy reserves critical. Eat now."
    )

    override suspend fun doWork(): Result {
        val store = VitalityStore(context)
        store.checkDailyReset()
        val config = store.getConfig()
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val currentHour = now.get(Calendar.HOUR_OF_DAY)

        // --- CHECK 1: IS THIS A PAIN FOLLOW-UP? ---
        if (inputData.getBoolean("CHECK_PAIN", false)) {

            // Check Sleep Window (unless overridden)
            val isSleepTime = currentHour < config.activeStartHour || currentHour >= config.activeEndHour
            val isOverride = config.clinicalOverride > 0f

            if (isSleepTime && !isOverride) {
                // User is sleeping, do not disturb.
                return Result.success()
            }

            // Trigger Notification
            triggerAlert(Protocol.CHEMISTRY, "", "STATUS CHECK: Update Pain Levels.")
            return Result.success()
        }

        // --- CHECK 2: STANDARD SENTINEL LOGIC ---

        // DEBUG FLAG CHECK — only acts if debug tools are switched on (see debug/DebugFlags.kt)
        val isDebug = inputData.getBoolean("IS_DEBUG", false)
        if (isDebug) {
            if (!DebugFlags.isEnabled(context)) return Result.success()
            val protoId = inputData.getInt("DEBUG_PROTO", 2)
            val debugProto = Protocol.values().firstOrNull { it.id == protoId } ?: Protocol.HYDRATION
            triggerAlert(debugProto, "", "DEBUG: Artificial System Stress Test.")
            return Result.success()
        }

        val currentTimeMs = System.currentTimeMillis()

        // 1. NUTRIENT CHECK — alert about the single most urgent pending meal slot, if any.
        val lastEat = store.getLastTime(Protocol.NUTRIENT)
        if (currentTimeMs - lastEat > 7200000) {
            val urgentMeal = config.mealTimes.withIndex()
                .filter { (_, time) -> isDueOrOverdue(time, currentMinutes) }
                .minByOrNull { (_, time) -> time }
            urgentMeal?.let { (index, _) ->
                triggerAlert(Protocol.NUTRIENT, "", MEAL_MESSAGES.getOrElse(index) { "Meal due." })
            }
        }

        // 2. MEDS CHECK — any number of medications, each with any number of daily
        // doses; alert about the single most urgent pending dose, if any.
        val urgentDose = config.allDoses()
            .filter { it.key !in store.completedKeys && isDueOrOverdue(it.time, currentMinutes) }
            .minByOrNull { it.time }
        urgentDose?.let { triggerAlert(Protocol.CHEMISTRY, it.key, "${it.medName} dose required.") }

        // 3. MAINTENANCE CHECK — any number of user-defined hygiene tasks;
        // alert about the single most urgent pending task, if any.
        val urgentTask = config.hygieneTasks
            .filter { SysConfig.hygieneKey(it.id) !in store.completedKeys && isDueOrOverdue(it.time, currentMinutes) }
            .minByOrNull { it.time }
        urgentTask?.let { triggerAlert(Protocol.MAINTENANCE, SysConfig.hygieneKey(it.id), "${it.label} due.") }

        // 4. HYDRATION CHECK
        checkHydrationDrift(config, currentMinutes, store.hydrationCount)

        // "Bleed" mechanic

        // Inside SentinelWorker.kt, at the end of doWork()
        val payload = VitalityMath.calculateSystemStatus(
            nutrientCount = store.nutrientCount,
            hydrationCount = store.hydrationCount,
            completedKeys = store.completedKeys,
            config = config
        )

        val intent = Intent("com.snakesan.overseer.UPDATE_STATUS").apply {
            setPackage("com.snakesan.overseer")
            putExtra("source_app", "VITALITY")
            putExtra("hp", payload.hp)
            putExtra("hyd_status", payload.hydStatus)
            putExtra("meal_status", payload.mealStatus)
        }
        context.sendBroadcast(intent)


        return Result.success()
    }

    // True when targetTime is due within LOOKAHEAD_MINUTES from now, or became
    // due up to an hour ago (still worth alerting about).
    private fun isDueOrOverdue(targetTime: Int, currentTime: Int): Boolean {
        val diff = targetTime - currentTime
        return diff <= LOOKAHEAD_MINUTES && diff > -60
    }

    private fun checkHydrationDrift(config: SysConfig, currentMinutes: Int, currentCount: Int) {
        val startMins = config.activeStartHour.toInt() * 60
        val endMins = config.activeEndHour.toInt() * 60

        if (currentMinutes in startMins..endMins) {
            val totalActiveDuration = endMins - startMins
            val elapsedActive = currentMinutes - startMins
            val expectedProgress = elapsedActive.toFloat() / totalActiveDuration.toFloat()
            val expectedMl = config.hydrationTargetMl * expectedProgress

            val currentMl = currentCount * 250
            val deficit = expectedMl - currentMl

            if (deficit > 375) {
                triggerAlert(Protocol.HYDRATION, "", "Hydration critical. You're drifting, Samurai.")
            }
        }
    }

    private fun triggerAlert(protocol: Protocol, itemKey: String, message: String) {
        val channelId = "vitality_ai"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Vitality AI", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "System Alerts"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("VITALITY.SYS")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        nm.notify(protocol.id, notification)

        val payload = AlertPayload(protocol.id, itemKey, message)

        try {
            val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
            nodes.forEach { node ->
                try {
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(node.id, "/sys/alert_phone", payload.toBytes()))
                } catch(e: Exception) { e.printStackTrace() }
            }
        } catch (e: Exception) { e.printStackTrace() }

        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50, 50, 100), -1))
        } else {
            v.vibrate(500)
        }
    }
}
