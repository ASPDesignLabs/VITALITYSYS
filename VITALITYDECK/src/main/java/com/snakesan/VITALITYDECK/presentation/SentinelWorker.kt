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
import java.nio.ByteBuffer
import java.util.Calendar

class SentinelWorker(val context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    private val LOOKAHEAD_MINUTES = 20

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
            triggerAlert(Protocol.CHEMISTRY, "STATUS CHECK: Update Pain Levels.")
            return Result.success()
        }

        // --- CHECK 2: STANDARD SENTINEL LOGIC ---

        // DEBUG FLAG CHECK
        val isDebug = inputData.getBoolean("IS_DEBUG", false)
        if (isDebug) {
            val protoId = inputData.getInt("DEBUG_PROTO", 2)
            val debugProto = Protocol.values().firstOrNull { it.id == protoId } ?: Protocol.HYDRATION
            triggerAlert(debugProto, "DEBUG: Artificial System Stress Test.")
            return Result.success()
        }

        val dayOfWeek = now.get(Calendar.DAY_OF_WEEK)
        val currentTimeMs = System.currentTimeMillis()

        // 1. NUTRIENT CHECK
        val lastEat = store.getLastTime(Protocol.NUTRIENT)
        if (currentTimeMs - lastEat > 7200000) {
            checkSchedule(config.meal1Time.toInt(), currentMinutes, Protocol.NUTRIENT, "Fuel cells empty. Intake imminent.")
            checkSchedule(config.meal2Time.toInt(), currentMinutes, Protocol.NUTRIENT, "Systems flagging. Refuel required.")
            checkSchedule(config.meal3Time.toInt(), currentMinutes, Protocol.NUTRIENT, "Running on fumes? Eat.")
        }

        // 2. MEDS CHECK
        val isWeekend = (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY)
        val targetMeds = if (isWeekend) config.medsWeekend else config.medsWeekday

        if (!store.medsTaken) {
            checkSchedule(targetMeds.toInt(), currentMinutes, Protocol.CHEMISTRY, "Chemistry imbalance detected. Dose required.")
        }

        // 3. MAINTENANCE CHECK
        if (!store.maintDone) {
            checkSchedule(config.maint1Time.toInt(), currentMinutes, Protocol.MAINTENANCE, "Hygiene check required.")
            checkSchedule(config.maint2Time.toInt(), currentMinutes, Protocol.MAINTENANCE, "System reset required.")
        }

        // 4. HYDRATION CHECK
        checkHydrationDrift(config, currentMinutes, store.hydrationCount)

        // "Bleed" mechanic

        // Inside SentinelWorker.kt, at the end of doWork()
        val payload = VitalityMath.calculateSystemStatus(
            nutrientCount = store.nutrientCount,
            hydrationCount = store.hydrationCount,
            medsTaken = store.medsTaken,
            maintDone = store.maintDone,
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

    private fun checkSchedule(targetTime: Int, currentTime: Int, protocol: Protocol, msg: String) {
        val diff = targetTime - currentTime
        if (diff <= LOOKAHEAD_MINUTES && diff > -60) {
             triggerAlert(protocol, msg)
        }
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
                triggerAlert(Protocol.HYDRATION, "Hydration critical. You're drifting, Samurai.")
            }
        }
    }

    private fun triggerAlert(protocol: Protocol, message: String) {
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
        
        val buffer = ByteBuffer.allocate(4)
        buffer.putInt(protocol.id)
        
        try {
            val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
            nodes.forEach { node -> 
                try {
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(node.id, "/sys/alert_phone", buffer.array()))
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
