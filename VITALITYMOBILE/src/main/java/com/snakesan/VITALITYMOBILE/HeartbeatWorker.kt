package com.snakesan.vitalitysys

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.tasks.Tasks
import com.snakesan.vitalitysys.data.VitalityDatabase
import java.util.Calendar

class HeartbeatWorker(val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    // Inside Phone's HeartbeatWorker.kt
    override suspend fun doWork(): Result {
        val db = VitalityDatabase.getDatabase(context)
        val c = Calendar.getInstance()
        val todayId = (c.get(Calendar.YEAR) * 1000) + c.get(Calendar.DAY_OF_YEAR)
        val stats = db.systemDao().getDailyStats(todayId) ?: return Result.success()

        // 1. Load User Config
        val prefs = context.getSharedPreferences("vitality_config", Context.MODE_PRIVATE)
        val config = prefs.getString("config_json", null)?.let {
            try { SysConfig.fromJson(org.json.JSONObject(it)) } catch (e: Exception) { null }
        } ?: SysConfig.DEFAULT

        // 2. Run the Unified Math Engine
        val payload = VitalityMath.calculateSystemStatus(
            nutrientCount = stats.nutrientCount,
            hydrationCount = stats.hydrationCount,
            completedKeys = decodeKeySet(stats.completedKeys),
            config = config
        )

        // 2b. Derive overcharge the same way MainActivity.calculateHealth()
        // does, from the same persisted start-time, so a backgrounded
        // heartbeat doesn't reset an in-progress overcharge streak.
        var overchargeStart = prefs.getLong("overcharge_start", 0L)
        val overcharge: Int
        if (payload.hp == 100) {
            if (overchargeStart == 0L) overchargeStart = System.currentTimeMillis()
            val elapsedMins = (System.currentTimeMillis() - overchargeStart) / 60000
            overcharge = ((elapsedMins * 50) / 60).toInt().coerceIn(0, 50)
        } else {
            overchargeStart = 0L
            overcharge = 0
        }
        prefs.edit().putLong("overcharge_start", overchargeStart).apply()

        // 3. Push to Data Layer — includes hyd_status/meal_status/overcharge
        // (previously omitted here, which reset them to defaults on the
        // watch every 15 minutes while the phone app was backgrounded).
        try {
            val putDataReq = PutDataMapRequest.create("/vitality_status").apply {
                dataMap.putInt("user_hp", payload.hp)
                dataMap.putInt("hyd_status", payload.hydStatus)
                dataMap.putInt("meal_status", payload.mealStatus)
                dataMap.putInt("overcharge", overcharge)
                dataMap.putLong("ts", System.currentTimeMillis())
            }.asPutDataRequest()

            putDataReq.setUrgent()
            Tasks.await(Wearable.getDataClient(context).putDataItem(putDataReq))
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.retry()
        }

        return Result.success()
    }
}
