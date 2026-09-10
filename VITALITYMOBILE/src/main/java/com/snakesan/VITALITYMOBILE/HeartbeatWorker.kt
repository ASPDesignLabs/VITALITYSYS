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

        // 3. Push to Data Layer
        try {
            val putDataReq = PutDataMapRequest.create("/vitality_status").apply {
                dataMap.putInt("user_hp", payload.hp)
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
