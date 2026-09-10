package com.snakesan.vitalitysys

import android.content.Context
import android.content.SharedPreferences
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
                .putBoolean("done_meds", false)
                .putBoolean("done_maint", false)
                .apply()
        }
    }

    var nutrientCount: Int
        get() = prefs.getInt("count_nutrient", 0)
        set(value) = prefs.edit().putInt("count_nutrient", value).apply()

    var hydrationCount: Int
        get() = prefs.getInt("count_hydration", 0)
        set(value) = prefs.edit().putInt("count_hydration", value).apply()

    var medsTaken: Boolean
        get() = prefs.getBoolean("done_meds", false)
        set(value) = prefs.edit().putBoolean("done_meds", value).apply()

    var maintDone: Boolean
        get() = prefs.getBoolean("done_maint", false)
        set(value) = prefs.edit().putBoolean("done_maint", value).apply()

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
    
    // UPDATED: Convert Floats to Ints for storage
    fun saveConfig(c: SysConfig) {
        prefs.edit()
            .putInt("conf_m1", c.meal1Time.toInt())
            .putInt("conf_m2", c.meal2Time.toInt())
            .putInt("conf_m3", c.meal3Time.toInt())
            .putInt("conf_md_w", c.medsWeekday.toInt())
            .putInt("conf_md_e", c.medsWeekend.toInt())
            .putInt("conf_hy_t", c.hydrationTargetMl.toInt())
            .putInt("conf_hy_s", c.activeStartHour.toInt())
            .putInt("conf_hy_e", c.activeEndHour.toInt())
            .putInt("conf_mt_1", c.maint1Time.toInt())
            .putInt("conf_mt_2", c.maint2Time.toInt())
            .putInt("conf_override", c.clinicalOverride.toInt())
            .apply()
    }

    // UPDATED: Convert Ints back to Floats for App use
    fun getConfig(): SysConfig {
        if (!prefs.contains("conf_m1")) return SysConfig.DEFAULT
        return SysConfig(
            prefs.getInt("conf_m1", 540).toFloat(), 
            prefs.getInt("conf_m2", 780).toFloat(), 
            prefs.getInt("conf_m3", 1140).toFloat(),
            prefs.getInt("conf_md_w", 480).toFloat(), 
            prefs.getInt("conf_md_e", 600).toFloat(),
            prefs.getInt("conf_hy_t", 3250).toFloat(), 
            prefs.getInt("conf_hy_s", 8).toFloat(), 
            prefs.getInt("conf_hy_e", 22).toFloat(),
            prefs.getInt("conf_mt_1", 450).toFloat(), 
            prefs.getInt("conf_mt_2", 1320).toFloat(),
            prefs.getInt("conf_override", 0).toFloat()
        )
    }
}
