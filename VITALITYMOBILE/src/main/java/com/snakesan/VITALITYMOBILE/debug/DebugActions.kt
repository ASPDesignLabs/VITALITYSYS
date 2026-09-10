package com.snakesan.vitalitysys.debug

import com.google.android.gms.wearable.Wearable
import com.snakesan.vitalitysys.MainActivity
import com.snakesan.vitalitysys.SysConfig
import com.snakesan.vitalitysys.data.DailyStats
import com.snakesan.vitalitysys.data.NotificationAudit
import com.snakesan.vitalitysys.data.SystemLog
import com.snakesan.vitalitysys.encodeKeySet
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

// All phone-side debug/data-management tools live here, isolated from the
// real app flow. Every entry point checks DebugFlags.isEnabled() itself so
// this stays inert even if something calls it outside DebugPanel's UI.

fun MainActivity.deleteLastHour() {
    if (!DebugFlags.isEnabled(this)) return
    lifecycleScope.launch(Dispatchers.IO) {
        db.systemDao().deleteLogsSince(System.currentTimeMillis() - 3600000L)
    }
}

fun MainActivity.deleteLast24Hours() {
    if (!DebugFlags.isEnabled(this)) return
    lifecycleScope.launch(Dispatchers.IO) {
        db.systemDao().deleteLogsSince(System.currentTimeMillis() - 86400000L)
    }
}

fun MainActivity.deleteCustomRange() {
    if (!DebugFlags.isEnabled(this)) return
    lifecycleScope.launch(Dispatchers.IO) {
        db.systemDao().deleteLogsInWindow(rangeStart, rangeEnd)
    }
}

fun MainActivity.wipeAllData() {
    if (!DebugFlags.isEnabled(this)) return
    lifecycleScope.launch(Dispatchers.IO) { db.systemDao().nukeAllLogs() }
}

// Fakes 30 minutes of overcharge time locally, then tells the watch to do
// the same, so the overcharge UI can be exercised without waiting an hour.
fun MainActivity.injectDebugOvercharge() {
    if (!DebugFlags.isEnabled(this)) return
    if (currentHP != 100f) return

    if (overchargeStartTime == 0L) overchargeStartTime = System.currentTimeMillis()
    overchargeStartTime -= (30 * 60 * 1000L)
    calculateHealth(Calendar.getInstance())

    Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
        nodes.forEach { node ->
            Wearable.getMessageClient(this).sendMessage(node.id, "/sys/debug_overcharge", ByteArray(0))
        }
    }
}

// --- FUZZY DATA INJECTION TOOL ---
fun MainActivity.injectFuzzyData(seed: Long = 1337L) {
    if (!DebugFlags.isEnabled(this)) return
    lifecycleScope.launch(Dispatchers.IO) {
        // 1. Wipe existing slate clean
        db.clearAllTables()

        val random = java.util.Random(seed)
        val now = System.currentTimeMillis()
        val dayMs = 86400000L
        var notifIdCounter = 1000

        // 2. Loop backwards through 30 days
        for (i in 30 downTo 0) {
            val dayStart = now - (i * dayMs)
            val cal = Calendar.getInstance().apply { timeInMillis = dayStart }
            val dayId = (cal.get(Calendar.YEAR) * 1000) + cal.get(Calendar.DAY_OF_YEAR)

            var nutCount = 0
            var hydCount = 0
            val dayCompletedKeys = mutableSetOf<String>()

            // Inner helper to simulate a notification lifecycle
            suspend fun generateEvent(protocol: String, hour: Int, minute: Int) {
                val issueTime = dayStart + (hour * 3600000L) + (minute * 60000L)
                val r = random.nextDouble()

                // ARTIFICIAL RESISTANCE: 85% chance to ignore/fail if it happens between 14:00 and 15:00
                val isResistanceHour = hour == 14
                val behavior = if (isResistanceHour && r < 0.85) {
                    if (random.nextBoolean()) "IGNORED" else "DISMISSED"
                } else {
                    // Normal Distribution
                    when {
                        r < 0.45 -> "FAST"       // < 5 mins
                        r < 0.70 -> "DELAY"      // 5-15 mins
                        r < 0.85 -> "WARNING"    // 15-60 mins
                        r < 0.95 -> "IGNORED"    // No answer
                        else -> "DISMISSED"      // Swiped away
                    }
                }

                var interactTime: Long? = null
                var fulfillTime: Long? = null
                var type: String? = null
                var status: String? = null

                when (behavior) {
                    "FAST" -> {
                        interactTime = issueTime + (random.nextInt(4) * 60000L)
                        fulfillTime = interactTime + 10000L
                        type = "CLICKED"
                        status = "SUCCESS"
                    }
                    "DELAY" -> {
                        interactTime = issueTime + ((5 + random.nextInt(10)) * 60000L)
                        fulfillTime = interactTime + 10000L
                        type = "CLICKED"
                        status = "SUCCESS"
                    }
                    "WARNING" -> {
                        interactTime = issueTime + ((16 + random.nextInt(40)) * 60000L)
                        fulfillTime = interactTime + 10000L
                        type = "CLICKED"
                        status = "CRITICAL_DELAY"
                    }
                    "IGNORED" -> {
                        type = "IGNORED"
                        status = "ABANDONED"
                    }
                    "DISMISSED" -> {
                        interactTime = issueTime + (random.nextInt(2) * 60000L)
                        type = "DISMISSED"
                        status = "ABANDONED"
                    }
                }

                // Save Audit
                db.systemDao().insertAudit(
                    NotificationAudit(
                        notificationId = notifIdCounter++,
                        protocolType = protocol,
                        timestampIssued = issueTime,
                        timestampInteracted = interactTime,
                        timestampFulfilled = fulfillTime,
                        interactionType = type,
                        finalStatus = status
                    )
                )

                // Save matching Log and update Counters if successful
                if (status == "SUCCESS" || status == "CRITICAL_DELAY") {
                    db.systemDao().insertLog(
                        SystemLog(
                            timestamp = fulfillTime ?: issueTime,
                            type = protocol, value = 1, note = "Auto-Fuzz"
                        )
                    )
                    when (protocol) {
                        "NUTRIENT" -> nutCount++
                        "HYDRATION" -> hydCount++
                        "CHEMISTRY" -> dayCompletedKeys.add(SysConfig.doseKey(medId = 1, doseIndex = 0))
                        "MAINTENANCE" -> dayCompletedKeys.add(SysConfig.hygieneKey(taskId = 1))
                    }
                }
            }

            // Build a standard day
            generateEvent("CHEMISTRY", 8, 30)
            generateEvent("NUTRIENT", 9, 0)
            generateEvent("HYDRATION", 10, 0)
            generateEvent("HYDRATION", 12, 0)
            generateEvent("NUTRIENT", 14, 0) // <-- The targeted Resistance Block
            generateEvent("HYDRATION", 16, 0)
            generateEvent("NUTRIENT", 19, 0)
            generateEvent("MAINTENANCE", 22, 0)

            // Inject 1 or 2 Random Pain Logs
            if (random.nextDouble() > 0.5) {
                val painLvl = 2 + random.nextInt(6)
                db.systemDao().insertLog(
                    SystemLog(
                        timestamp = dayStart + (random.nextInt(12) + 8) * 3600000L,
                        type = "PAIN", value = painLvl, note = "Synthetic Pain Event"
                    )
                )
            }

            // Finalize Daily Stats
            db.systemDao().setDailyStats(
                DailyStats(
                    dayId = dayId, nutrientCount = nutCount, hydrationCount = hydCount,
                    completedKeys = encodeKeySet(dayCompletedKeys)
                )
            )
        }

        // Force state refresh
        withContext(Dispatchers.Main) {
            calculateHealth(Calendar.getInstance())
        }
    }
}
