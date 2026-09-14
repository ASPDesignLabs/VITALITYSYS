package com.snakesan.vitalitysys

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Calendar

class VitalityStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("vitality_db", Context.MODE_PRIVATE)

    fun checkDailyReset() {
        // year*1000 + dayOfYear (matches the phone's getTodayId()) — plain
        // DAY_OF_YEAR alone would wrongly skip a reset if the watch went
        // untouched for close to exactly 365/366 days.
        val cal = Calendar.getInstance()
        val currentDayId = (cal.get(Calendar.YEAR) * 1000) + cal.get(Calendar.DAY_OF_YEAR)
        val lastDayId = prefs.getInt("day_id", -1)
        if (lastDayId != currentDayId) {
            prefs.edit()
                .putInt("day_id", currentDayId)
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

    // Drops a single delivered log rather than the whole queue, so a flush
    // that fails partway through doesn't resend logs that already made it
    // across (see MainActivity.flushPainLogs).
    fun removePendingPainLog(timestamp: Long, level: Int) {
        val remaining = getPendingPainLogs().filterNot { it.first == timestamp && it.second == level }
        val newString = remaining.joinToString("|") { "${it.first}:${it.second}" }
        prefs.edit().putString("pending_pain", newString).apply()
    }

    // --- PENDING TELEMETRY (Offline Queue) ---
    // Mirrors the pending-pain-log queue above: a watch-side completion
    // (tapping "LOG ENTRY"/"ALL CLEAR") must still reach the phone even if
    // the watch happens to be briefly disconnected right when it's tapped,
    // or the phone's audit trail will wrongly flag a real response as
    // ignored once its 5-minute check fires. itemKey can itself contain ":"
    // (see SysConfig.doseKey), so it's reconstructed from everything between
    // the first and last ":" rather than a fixed split index.
    fun addPendingTelemetry(protocolId: Int, itemKey: String, timestamp: Long) {
        val currentString = prefs.getString("pending_telemetry", "") ?: ""
        val entry = "$protocolId:$itemKey:$timestamp"
        val newString = if (currentString.isEmpty()) entry else "$currentString|$entry"
        prefs.edit().putString("pending_telemetry", newString).apply()
    }

    fun getPendingTelemetry(): List<Triple<Int, String, Long>> {
        val raw = prefs.getString("pending_telemetry", "") ?: return emptyList()
        if (raw.isEmpty()) return emptyList()

        return raw.split("|").mapNotNull { entry ->
            val parts = entry.split(":")
            // Always at least 3: protocolId, itemKey (possibly empty, but
            // still bounded by its own ":"s), timestamp.
            if (parts.size < 3) return@mapNotNull null
            try {
                val protocolId = parts.first().toInt()
                val timestamp = parts.last().toLong()
                val itemKey = parts.subList(1, parts.size - 1).joinToString(":")
                Triple(protocolId, itemKey, timestamp)
            } catch (e: NumberFormatException) { null }
        }
    }

    // Drops a single delivered entry rather than the whole queue, so a flush
    // that fails partway through doesn't resend telemetry that already made
    // it across (see MainActivity.flushPendingTelemetry).
    fun removePendingTelemetry(protocolId: Int, itemKey: String, timestamp: Long) {
        val remaining = getPendingTelemetry().filterNot {
            it.first == protocolId && it.second == itemKey && it.third == timestamp
        }
        val newString = remaining.joinToString("|") { "${it.first}:${it.second}:${it.third}" }
        prefs.edit().putString("pending_telemetry", newString).apply()
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
