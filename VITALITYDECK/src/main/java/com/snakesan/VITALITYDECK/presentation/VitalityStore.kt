package com.snakesan.vitalitysys

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Calendar

class VitalityStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("vitality_db", Context.MODE_PRIVATE)

    fun checkDailyReset() {
        val lastDay = prefs.getInt("day_of_year", -1)
        val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (lastDay != currentDay) {
            prefs.edit()
                .putInt("day_of_year", currentDay)
                .putInt("count_nutrient", 0)
                .putInt("count_hydration", 0)
                .putString("completed_keys", "")
                .apply()
        }
    }

    var nutrientCount: Int
        get() = prefs.getInt("count_nutrient", 0)
        set(value) = prefs.edit().putInt("count_nutrient", value).apply()

    var hydrationCount: Int
        get() = prefs.getInt("count_hydration", 0)
        set(value) = prefs.edit().putInt("count_hydration", value).apply()

    // Completed dose/hygiene-task keys for today (see SysConfig.doseKey /
    // hygieneKey). Replaces the old single medsTaken/maintDone booleans now
    // that Chemistry and Maintenance can each hold any number of
    // user-defined reminders.
    var completedKeys: Set<String>
        get() = decodeKeySet(prefs.getString("completed_keys", "") ?: "")
        set(value) = prefs.edit().putString("completed_keys", encodeKeySet(value)).apply()

    // Track Last Pain Level for Sentinel Logic
    var lastPainLevel: Int
        get() = prefs.getInt("last_pain_lvl", 0)
        set(value) = prefs.edit().putInt("last_pain_lvl", value).apply()

    fun setLastTime(protocol: Protocol, time: Long) = prefs.edit().putLong("last_time_${protocol.id}", time).apply()
    fun getLastTime(protocol: Protocol): Long = prefs.getLong("last_time_${protocol.id}", 0L)

    // --- PENDING PAIN LOGS (Offline Queue) ---
    fun addPendingPainLog(timestamp: Long, level: Int) {
        val currentString = prefs.getString("pending_pain", "") ?: ""
        val entry = "$timestamp:$level"
        val newString = if (currentString.isEmpty()) entry else "$currentString|$entry"
        prefs.edit().putString("pending_pain", newString).apply()
    }

    fun getPendingPainLogs(): List<Pair<Long, Int>> {
        val raw = prefs.getString("pending_pain", "") ?: return emptyList()
        if (raw.isEmpty()) return emptyList()

        return raw.split("|").mapNotNull {
            val parts = it.split(":")
            if (parts.size == 2) {
                try {
                    Pair(parts[0].toLong(), parts[1].toInt())
                } catch (e: NumberFormatException) { null }
            } else null
        }
    }

    fun clearPendingPainLogs() {
        prefs.edit().putString("pending_pain", "").apply()
    }

    // --- CONFIGURATION ---

    fun saveConfig(c: SysConfig) {
        prefs.edit().putString("conf_json", c.toJson().toString()).apply()
    }

    fun getConfig(): SysConfig {
        val raw = prefs.getString("conf_json", null) ?: return SysConfig.DEFAULT
        return try { SysConfig.fromJson(JSONObject(raw)) } catch (e: Exception) { SysConfig.DEFAULT }
    }
}
