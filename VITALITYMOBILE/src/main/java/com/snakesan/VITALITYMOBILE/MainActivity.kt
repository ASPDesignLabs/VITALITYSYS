package com.snakesan.vitalitysys

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.wearable.*
import com.snakesan.vitalitysys.data.DailyStats
import com.snakesan.vitalitysys.data.NotificationAudit
import com.snakesan.vitalitysys.data.SystemLog
import com.snakesan.vitalitysys.data.VitalityDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.Calendar
import java.util.concurrent.TimeUnit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener, DataClient.OnDataChangedListener {
    // --- STATE ---
    var nutrientCount by mutableIntStateOf(0)
    var hydrationCount by mutableIntStateOf(0)
    // Completed dose/hygiene-task keys for today (see SysConfig.doseKey /
    // hygieneKey). Replaces the old single medsTaken/hygieneDone booleans
    // now that Chemistry and Maintenance can each hold any number of
    // user-defined reminders.
    var completedKeys by mutableStateOf<Set<String>>(emptySet())
    // Which specific item an in-flight interruption/notification is about
    // (empty for NUTRIENT/HYDRATION, which have no sub-items).
    var pendingItemKey by mutableStateOf("")

    var currentHP by mutableFloatStateOf(100f)

    private val activeDebugBleeds = mutableStateMapOf<Int, Long>()
    var pendingPainLevel by mutableIntStateOf(0)

    var rangeStart by mutableLongStateOf(System.currentTimeMillis() - 604800000L)
    var rangeEnd by mutableLongStateOf(System.currentTimeMillis())

    // Schedule config — user-editable lists rather than fixed slots.
    var mealTimes = mutableStateListOf(540, 780, 1140)
    var medications = mutableStateListOf(MedicationConfig(id = 1, name = "Medication", times = listOf(480)))
    var hygieneTasks = mutableStateListOf(
        HygieneTaskConfig(id = 1, label = "Morning Routine", time = 450),
        HygieneTaskConfig(id = 2, label = "Evening Routine", time = 1320)
    )
    // Monotonic id counters so new medications/hygiene tasks never reuse an
    // id that a stale completedKeys entry or pending notification refers to.
    var nextMedId by mutableIntStateOf(2)
    var nextHygieneId by mutableIntStateOf(3)

    var hydrationTarget by mutableFloatStateOf(3250f)
    var activeStart by mutableFloatStateOf(8f)
    var activeEnd by mutableFloatStateOf(22f)

    var clinicalOverride by mutableStateOf(false)

    var uploadState by mutableStateOf(UploadState.IDLE)
    var appMode by mutableStateOf(AppMode.DASHBOARD)
    var activeProtocol by mutableStateOf<Protocol?>(null)
    var userContext by mutableStateOf("")

    // Overcharge variables
    var overchargeStartTime by mutableLongStateOf(0L)
    var currentOvercharge by mutableIntStateOf(0)

    lateinit var db: VitalityDatabase

    val healthConnectManager by lazy { HealthConnectManager(this) }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private fun getTodayId(): Int {
        val c = Calendar.getInstance()
        return (c.get(Calendar.YEAR) * 1000) + c.get(Calendar.DAY_OF_YEAR)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request permissions on launch if we don't have them
        val healthPermissionLauncher = registerForActivityResult(
                   androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
               ) { granted ->
                   if (granted.containsAll(healthConnectManager.requiredPermissions)) {
                           Log.d("VITALITY.SYS", "Health Connect link optimized.")
                       }
               }

        lifecycleScope.launch {
                   if (!healthConnectManager.hasAllPermissions()) {
                       healthPermissionLauncher.launch(healthConnectManager.requiredPermissions)
                       }
               }

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val prefs = getSharedPreferences("vitality_config", Context.MODE_PRIVATE)
        val storedConfig = prefs.getString("config_json", null)?.let {
            try { SysConfig.fromJson(org.json.JSONObject(it)) } catch (e: Exception) { null }
        } ?: SysConfig.DEFAULT
        applyConfig(storedConfig)

        db = VitalityDatabase.getDatabase(this)

        // --- LOAD STATE FROM DB ---
        lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                db.systemDao().getDailyStats(getTodayId())
            }
            if (stats != null) {
                nutrientCount = stats.nutrientCount
                hydrationCount = stats.hydrationCount
                completedKeys = decodeKeySet(stats.completedKeys)
                calculateHealth(Calendar.getInstance())
            }
        }

        // --- SCHEDULE BACKGROUND WORKER (FITBIT STYLE) ---
        val heartbeatRequest = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "VitalityHeartbeat",
            ExistingPeriodicWorkPolicy.KEEP,
            heartbeatRequest
        )

        Wearable.getMessageClient(this).addListener(this)
        Wearable.getDataClient(this).addListener(this)
        handleIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        lifecycleScope.launch {
            // repeatOnLifecycle suspends when the app is minimized and resumes when opened
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    calculateHealth(Calendar.getInstance())
                    // 5-second tick for incredibly responsive UI without draining background battery
                    delay(5000)
                }
            }
        }

        setContent { NeonTheme { VitalityOrchestrator(this) } }
    }

    // Builds a SysConfig snapshot from the phone's live editable state.
    fun currentConfig(): SysConfig = SysConfig(
        mealTimes = mealTimes.toList(),
        medications = medications.toList(),
        hygieneTasks = hygieneTasks.toList(),
        hydrationTargetMl = hydrationTarget,
        activeStartHour = activeStart,
        activeEndHour = activeEnd,
        clinicalOverride = if (clinicalOverride) 1f else 0f
    )

    // Populates the editable state lists (and id counters) from a loaded/synced config.
    private fun applyConfig(config: SysConfig) {
        mealTimes.clear(); mealTimes.addAll(config.mealTimes)
        medications.clear(); medications.addAll(config.medications)
        hygieneTasks.clear(); hygieneTasks.addAll(config.hygieneTasks)
        hydrationTarget = config.hydrationTargetMl
        activeStart = config.activeStartHour
        activeEnd = config.activeEndHour
        clinicalOverride = config.clinicalOverride > 0f
        nextMedId = (medications.maxOfOrNull { it.id } ?: 0) + 1
        nextHygieneId = (hygieneTasks.maxOfOrNull { it.id } ?: 0) + 1
    }

    fun saveAndPushConfig() {
        val config = currentConfig()
        val json = config.toJson().toString()

        // Save locally for the HeartbeatWorker and next app launch
        val prefs = getSharedPreferences("vitality_config", Context.MODE_PRIVATE)
        prefs.edit().putString("config_json", json).apply()

        // Push to Watch
        val putDataReq = PutDataMapRequest.create("/vitality_config").apply {
            dataMap.putString("config_json", json)
            dataMap.putLong("ts", System.currentTimeMillis())
        }.asPutDataRequest()

        putDataReq.setUrgent()
        Wearable.getDataClient(this).putDataItem(putDataReq)
    }

    fun completeItem(key: String) {
        if (key.isEmpty()) return
        completedKeys = completedKeys + key
        persistState()
        calculateHealth(Calendar.getInstance())
    }

    fun persistState() {
        val dayId = getTodayId()
        val stats = DailyStats(
            dayId = dayId,
            nutrientCount = nutrientCount,
            hydrationCount = hydrationCount,
            completedKeys = encodeKeySet(completedKeys),
            lastUpdated = System.currentTimeMillis()
        )
        lifecycleScope.launch(Dispatchers.IO) {
            db.systemDao().setDailyStats(stats)
        }
    }

    // --- DAMAGE LOGIC ENGINE ---
    fun calculateHealth(now: Calendar) {
        // 1. Build the config from the phone's live state variables
        val currentConfig = currentConfig()

        // 2. Feed it to the unified Math Engine
        val payload = VitalityMath.calculateSystemStatus(
            nutrientCount = nutrientCount,
            hydrationCount = hydrationCount,
            completedKeys = completedKeys,
            config = currentConfig
        )

        // 3. Apply arbitrary debug penalties (from notifications/testing)
        var totalDebugDmg = 0
        activeDebugBleeds.forEach { (_, startTime) ->
            val elapsedMins = (System.currentTimeMillis() - startTime) / 60000
            totalDebugDmg += 25 + elapsedMins.toInt()
        }

        val finalHp = (payload.hp - totalDebugDmg).coerceIn(0, 100)
        currentHP = finalHp.toFloat()

        // --- NEW OVERCHARGE LOGIC ---
        if (finalHp == 100) {
            if (overchargeStartTime == 0L) {
                overchargeStartTime = System.currentTimeMillis()
            }
            val elapsedMins = (System.currentTimeMillis() - overchargeStartTime) / 60000
            // 60 minutes = 50 points. Coerce to a max of 50.
            currentOvercharge = ((elapsedMins * 50) / 60).toInt().coerceIn(0, 50)
        } else {
            // Glass Cannon: Instantly shatter momentum
            overchargeStartTime = 0L
            currentOvercharge = 0
        }

        broadcastHpToOverseer(currentHP)
    }

    // --- BROADCAST TO WATCH FACE ---
    private fun broadcastHpToOverseer(hpValue: Float) {
        val finalHp = hpValue.toInt().coerceIn(0, 100)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Need the full payload from the last calculation to keep Watch informed
                val payload = VitalityMath.calculateSystemStatus(
                    nutrientCount, hydrationCount, completedKeys, currentConfig()
                )

                val dataMapRequest = PutDataMapRequest.create("/vitality_status").apply {
                    dataMap.putInt("user_hp", finalHp)
                    dataMap.putInt("hyd_status", payload.hydStatus)
                    dataMap.putInt("meal_status", payload.mealStatus)
                    dataMap.putInt("overcharge", currentOvercharge) // <-- Send Overcharge!
                    dataMap.putLong("ts", System.currentTimeMillis())
                }

                val request = dataMapRequest.asPutDataRequest().setUrgent()
                com.google.android.gms.tasks.Tasks.await(
                    Wearable.getDataClient(this@MainActivity).putDataItem(request)
                )
            } catch (e: Exception) {
                Log.e("VITALITY_LINK", "!!! FAILED", e)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                if (event.dataItem.uri.path == "/vitality_state") {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    runOnUiThread {
                        nutrientCount = dataMap.getInt("nutrients", nutrientCount)
                        hydrationCount = dataMap.getInt("hydration", hydrationCount)
                        completedKeys = decodeKeySet(dataMap.getString("completedKeys") ?: "")

                        calculateHealth(Calendar.getInstance())
                        persistState()
                    }
                }
            }
        }
    }

    private fun handleIntent(intent: Intent) {
        if (intent.hasExtra("PROTOCOL_ID")) {
            val protoId = intent.getIntExtra("PROTOCOL_ID", -1)
            if (protoId == 99) {
                val level = intent.getIntExtra("PAIN_LEVEL", 0)
                pendingPainLevel = level
                appMode = AppMode.INTERRUPT_CAPTURE
                userContext = ""
                activeProtocol = Protocol.CHEMISTRY
            } else if (protoId != -1) {
                activeProtocol = Protocol.values().find { it.id == protoId }
                pendingItemKey = intent.getStringExtra("ITEM_KEY") ?: ""
                if (activeProtocol != null) {
                    if (activeProtocol == Protocol.NUTRIENT) {
                        appMode = AppMode.NUTRITION_CAPTURE
                    } else {
                        appMode = AppMode.INTERRUPT_CAPTURE
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Wearable.getMessageClient(this).removeListener(this)
        Wearable.getDataClient(this).removeListener(this)
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == "/sys/pain_log") {
            val buffer = ByteBuffer.wrap(event.data)
            val painLevel = buffer.int
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra("PROTOCOL_ID", 99)
                putExtra("PAIN_LEVEL", painLevel)
            }
            startActivity(intent)
        }

        if (event.path == "/sys/telemetry") {
            val telemetry = TelemetryEvent.fromBytes(event.data)

            activeDebugBleeds.remove(telemetry.protocolId)

            // NEW: Intercept Nutrient (Meal) telemetry and force the phone to wake up!
            if (telemetry.protocolId == Protocol.NUTRIENT.id) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    putExtra("PROTOCOL_ID", Protocol.NUTRIENT.id)
                }
                startActivity(intent)
                return
            }

            // Otherwise, standard background processing
            when (telemetry.protocolId) {
                0 -> nutrientCount++ // Failsafe, though intercepted above
                1, 3 -> if (telemetry.itemKey.isNotEmpty()) completedKeys = completedKeys + telemetry.itemKey
                2 -> {
                    hydrationCount++
                    // Forward the watch log directly to Health Connect!
                    lifecycleScope.launch(Dispatchers.IO) {
                        healthConnectManager.logWater(250.0)
                    }
                }
            }
            calculateHealth(Calendar.getInstance())
            persistState()
        }

        if (event.path == "/sys/alert_phone") {
            val alert = AlertPayload.fromBytes(event.data)
            val protocol = Protocol.values().firstOrNull { it.id == alert.protocolId }
            if (protocol != null) {
                runOnUiThread {
                    activeDebugBleeds[alert.protocolId] = System.currentTimeMillis()
                    calculateHealth(Calendar.getInstance())
                }
            }
        }
    }

    // --- NEW: AUDIT FULFILLMENT ---
    fun fulfillProtocolAudit(protocolId: Int) {
        val now = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.IO) {
            val audit = db.systemDao().getAuditByNotificationId(protocolId)
            if (audit != null) {
                // Time delta in seconds
                val timeDelta = (now - audit.timestampIssued) / 1000
                val finalStat = if (timeDelta > 3600) "CRITICAL_DELAY" else "SUCCESS"

                db.systemDao().updateAudit(
                    audit.copy(
                        timestampInteracted = audit.timestampInteracted ?: now,
                        timestampFulfilled = now,
                        interactionType = audit.interactionType ?: "CLICKED",
                        finalStatus = finalStat
                    )
                )
            }
        }
    }

    // Now these functions are safely back in the main class:

    fun commitPainLog(note: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.systemDao().insertLog(
                    SystemLog(
                        timestamp = System.currentTimeMillis(),
                        type = "PAIN",
                        value = pendingPainLevel,
                        note = note
                    )
                )
            }
            pendingPainLevel = 0
            appMode = AppMode.DASHBOARD
            activeProtocol = null
        }
    }

    fun deleteLastHour() {
        lifecycleScope.launch(Dispatchers.IO) {
            db.systemDao().deleteLogsSince(System.currentTimeMillis() - 3600000L)
        }
    }

    fun deleteLast24Hours() {
        lifecycleScope.launch(Dispatchers.IO) {
            db.systemDao().deleteLogsSince(System.currentTimeMillis() - 86400000L)
        }
    }

    fun deleteCustomRange() {
        lifecycleScope.launch(Dispatchers.IO) {
            db.systemDao().deleteLogsInWindow(rangeStart, rangeEnd)
        }
    }

    fun wipeAllData() {
        lifecycleScope.launch(Dispatchers.IO) { db.systemDao().nukeAllLogs() }
    }
    // --- FUZZY DATA INJECTION TOOL ---
    fun injectFuzzyData(seed: Long = 1337L) {
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
                        com.snakesan.vitalitysys.data.NotificationAudit(
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
                            com.snakesan.vitalitysys.data.SystemLog(
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
                        com.snakesan.vitalitysys.data.SystemLog(
                            timestamp = dayStart + (random.nextInt(12) + 8) * 3600000L,
                            type = "PAIN", value = painLvl, note = "Synthetic Pain Event"
                        )
                    )
                }

                // Finalize Daily Stats
                db.systemDao().setDailyStats(
                    com.snakesan.vitalitysys.data.DailyStats(
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
}