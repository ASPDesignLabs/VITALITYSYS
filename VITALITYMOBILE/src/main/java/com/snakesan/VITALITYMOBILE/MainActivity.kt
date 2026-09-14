package com.snakesan.vitalitysys

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.wearable.*
import com.snakesan.vitalitysys.data.DailyStats
import com.snakesan.vitalitysys.data.SystemLog
import com.snakesan.vitalitysys.data.VitalityDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    // A photo taken as an alternative/companion to typing "what were you
    // doing" during INTERRUPT_CAPTURE — carried through ACTION/RESTORE the
    // same way userContext is, purely to jog memory; never persisted to disk
    // beyond the app's own files dir, never written to the DB.
    var userContextPhotoUri by mutableStateOf<Uri?>(null)
    private var photoCaptureTarget: Uri? = null
    // True when the protocol this overlay is showing got fulfilled by
    // something other than this exact screen's own "CONFIRM COMPLETION" (the
    // watch, most commonly) while the phone was still mid-response —
    // see reconcileInFlightOverlay().
    var completedViaWatch by mutableStateOf(false)
    // True when NUTRITION_CAPTURE was reached because the watch already
    // logged (and counted) a meal locally and just needs macro detail from
    // the phone — so onLogMeal must not increment nutrientCount again.
    var nutrientLoggedViaWatch by mutableStateOf(false)
    var escapeDifficulty by mutableStateOf(EscapeDifficulty.STANDARD)

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

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) launchCameraCapture() }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        userContextPhotoUri = if (success) photoCaptureTarget else null
        photoCaptureTarget = null
    }

    // Entry point for the "ATTACH PHOTO" option in the interrupt-capture
    // screen — an alternative to typing what you were doing.
    fun capturePhoto() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            launchCameraCapture()
        }
    }

    private fun launchCameraCapture() {
        val photosDir = File(filesDir, "interrupt_photos").apply { mkdirs() }
        val file = File(photosDir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        photoCaptureTarget = uri
        takePictureLauncher.launch(uri)
    }

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
        // HeartbeatWorker (background) shares this same key so it can derive
        // the same overcharge value the Activity would while backgrounded.
        overchargeStartTime = prefs.getLong("overcharge_start", 0L)

        db = VitalityDatabase.getDatabase(this)
        activeInstance = this

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
        clinicalOverride = if (clinicalOverride) 1f else 0f,
        escapeDifficulty = escapeDifficulty.id
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
        escapeDifficulty = EscapeDifficulty.fromId(config.escapeDifficulty)
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
        val previousOverchargeStart = overchargeStartTime
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

        // Persist so HeartbeatWorker (background) can derive the same
        // overcharge value the Activity would, instead of always seeing 0.
        if (overchargeStartTime != previousOverchargeStart) {
            getSharedPreferences("vitality_config", Context.MODE_PRIVATE).edit()
                .putLong("overcharge_start", overchargeStartTime).apply()
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
            completedViaWatch = false
            if (protoId == 99) {
                val level = intent.getIntExtra("PAIN_LEVEL", 0)
                pendingPainLevel = level
                appMode = AppMode.INTERRUPT_CAPTURE
                userContext = ""
                userContextPhotoUri = null
                activeProtocol = Protocol.CHEMISTRY
            } else if (protoId != -1) {
                val protocol = Protocol.values().find { it.id == protoId }
                activeProtocol = protocol
                pendingItemKey = intent.getStringExtra("ITEM_KEY") ?: ""
                nutrientLoggedViaWatch = intent.getBooleanExtra("LOGGED_VIA_WATCH", false)

                if (protocol != null) {
                    val resumeMode = intent.getStringExtra("RESUME_MODE")
                    if (resumeMode != null) {
                        // Resuming off the sticky in-progress notification —
                        // restore exactly where the user left off instead of
                        // restarting capture from scratch.
                        userContext = intent.getStringExtra("RESUME_CONTEXT") ?: ""
                        val photoUriString = intent.getStringExtra("RESUME_PHOTO_URI")
                        userContextPhotoUri = photoUriString?.let { Uri.parse(it) }
                        appMode = if (resumeMode == "ACTION") AppMode.INTERRUPT_ACTION else AppMode.INTERRUPT_CAPTURE
                        StickyStatusNotifier.notifyInProgress(
                            this, protocol, pendingItemKey, userContext, resumeMode, photoUriString
                        )
                    } else if (protocol == Protocol.NUTRIENT) {
                        userContext = ""
                        userContextPhotoUri = null
                        appMode = AppMode.NUTRITION_CAPTURE
                        // A watch-triggered meal log isn't "responding to an
                        // interruption" — there's no captured context to nag
                        // about, just macro detail left to fill in.
                        if (!nutrientLoggedViaWatch) {
                            StickyStatusNotifier.notifyInProgress(this, protocol, "", "", "CAPTURE", null)
                        }
                    } else {
                        userContext = ""
                        userContextPhotoUri = null
                        appMode = AppMode.INTERRUPT_CAPTURE
                        StickyStatusNotifier.notifyInProgress(this, protocol, pendingItemKey, "", "CAPTURE", null)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Wearable.getMessageClient(this).removeListener(this)
        Wearable.getDataClient(this).removeListener(this)
        if (activeInstance === this) activeInstance = null
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
            val protocol = Protocol.values().firstOrNull { it.id == telemetry.protocolId }

            runOnUiThread { activeDebugBleeds.remove(telemetry.protocolId) }

            // Intercept Nutrient (Meal) telemetry and force the phone to wake up!
            if (protocol == Protocol.NUTRIENT) {
                // The watch already bumped its own nutrientCount locally and
                // will push the authoritative total here via the Data Layer
                // (/vitality_state, sent alongside this message) — don't add
                // to it again. Just close out any audit/sticky-notification
                // trail, then force the phone open so the user can enter the
                // macro detail only the phone UI supports.
                markCompletedExternally(this, Protocol.NUTRIENT, "", "WATCH_LOGGED")
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    putExtra("PROTOCOL_ID", Protocol.NUTRIENT.id)
                    putExtra("LOGGED_VIA_WATCH", true)
                }
                startActivity(intent)
                return
            }

            // HYDRATION / CHEMISTRY / MAINTENANCE: counts and completedKeys
            // arrive authoritatively via the same /vitality_state Data Layer
            // sync the watch always pushes right before this message — this
            // handler used to *also* apply the delta itself, which raced
            // that sync and could silently double-count depending on which
            // channel landed first. It only needs to react to *that this
            // happened*: close the audit/notification trail, and if the
            // phone is mid-response to this exact item right now, fast-
            // forward that overlay instead of leaving it waiting on a
            // "CONFIRM COMPLETION" tap for something already done elsewhere.
            if (protocol != null) {
                if (protocol == Protocol.HYDRATION) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        healthConnectManager.logWater(250.0)
                    }
                }
                markCompletedExternally(this, protocol, telemetry.itemKey, "WATCH_LOGGED")
            }
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

    // --- AUDIT FULFILLMENT ---
    // interactionType defaults to "CLICKED" for the in-app confirm/meal-log
    // paths; markCompletedExternally() passes "WATCH_LOGGED" for a watch-
    // driven completion so the audit trail records which device actually
    // responded. Also closes out the alert + sticky in-progress notification
    // for this protocol, since "fulfilled" always means there's nothing left
    // to notify about — regardless of which caller reached this point.
    fun fulfillProtocolAudit(protocolId: Int, interactionType: String = "CLICKED") {
        NotificationManagerCompat.from(this).cancel(protocolId)
        StickyStatusNotifier.clear(this, protocolId)
        lifecycleScope.launch(Dispatchers.IO) { applyAuditFulfillment(db, protocolId, interactionType) }
    }

    // Cancels an in-flight interrupt without completing it. Used by the
    // configurable escape (see EscapeDifficulty / VitalityUI's BackHandler)
    // and any explicit "skip" action. Deliberately does NOT touch the alert
    // or sticky in-progress notifications — the task is still undone, so the
    // nag should keep standing until it's actually fulfilled.
    fun abandonInterruption() {
        val protocol = activeProtocol
        if (protocol != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val audit = db.systemDao().getAuditByNotificationId(protocol.id)
                if (audit != null && audit.interactionType == null) {
                    db.systemDao().updateAudit(
                        audit.copy(
                            timestampInteracted = System.currentTimeMillis(),
                            interactionType = "DISMISSED",
                            finalStatus = "ABANDONED"
                        )
                    )
                }
            }
        }
        appMode = AppMode.DASHBOARD
        activeProtocol = null
        userContext = ""
        userContextPhotoUri = null
        pendingItemKey = ""
        pendingPainLevel = 0
        completedViaWatch = false
        nutrientLoggedViaWatch = false
    }

    // Called when the currently-displayed interrupt overlay's protocol (and,
    // for itemized ones, the exact item) matches something that was just
    // fulfilled through another channel — i.e. the watch. Fast-forwards past
    // whatever "CONFIRM COMPLETION" step is still on screen so the user
    // isn't left staring at a stale prompt for something already done, and
    // so that prompt's own completion button can't fire a second time and
    // double-count. NUTRITION_CAPTURE is deliberately excluded: the watch
    // can't supply macro detail, so that step always still has to happen.
    private fun reconcileInFlightOverlay(protocol: Protocol, itemKey: String) {
        // A pain-symptom capture reuses Protocol.CHEMISTRY's id/color purely
        // for styling — it isn't actually responding to any CHEMISTRY alert,
        // so a real chemistry-dose completion arriving mid-pain-capture must
        // not yank the user out of describing their symptoms.
        if (pendingPainLevel > 0) return
        val matches = (appMode == AppMode.INTERRUPT_CAPTURE || appMode == AppMode.INTERRUPT_ACTION) &&
            activeProtocol == protocol &&
            (itemKey.isEmpty() || itemKey == pendingItemKey)
        if (!matches) return
        runOnUiThread {
            completedViaWatch = true
            appMode = AppMode.INTERRUPT_RESTORE
        }
    }

    companion object {
        // The one live MainActivity, if any — lets a BroadcastReceiver or a
        // Wearable callback (which only has a Context) reconcile a
        // currently-displayed interrupt overlay, and share the same DB
        // instance, without a separate messaging channel back into the
        // running Activity. Cleared in onDestroy.
        @Volatile private var activeInstance: MainActivity? = null

        fun isActivelyRespondingTo(protocolId: Int): Boolean {
            val instance = activeInstance ?: return false
            // Same carve-out as reconcileInFlightOverlay: a pain capture
            // borrows CHEMISTRY's id/color without actually responding to a
            // CHEMISTRY alert, so it must not shield an unrelated real
            // chemistry-dose notification from being recorded as dismissed.
            if (instance.pendingPainLevel > 0) return false
            return instance.appMode != AppMode.DASHBOARD && instance.activeProtocol?.id == protocolId
        }

        // Records that a protocol got fulfilled through a channel other than
        // this exact screen's own "CONFIRM COMPLETION" tap — a watch button,
        // or the notification's inline quick-reply — so the audit trail, the
        // sticky status notification, and (if this exact protocol is what's
        // currently on screen) the interrupt overlay all agree the task is
        // done instead of silently drifting out of sync with whichever
        // device the user actually used.
        fun markCompletedExternally(context: Context, protocol: Protocol, itemKey: String, interactionType: String) {
            val instance = activeInstance
            if (instance != null) {
                instance.fulfillProtocolAudit(protocol.id, interactionType)
                instance.reconcileInFlightOverlay(protocol, itemKey)
            } else {
                // No live Activity (app fully backgrounded/killed) — cancel
                // notifications and write the audit update directly.
                NotificationManagerCompat.from(context).cancel(protocol.id)
                StickyStatusNotifier.clear(context, protocol.id)
                val db = VitalityDatabase.getDatabase(context)
                CoroutineScope(Dispatchers.IO).launch { applyAuditFulfillment(db, protocol.id, interactionType) }
            }
        }

        // Shared by fulfillProtocolAudit (instance, has a live `db`) and the
        // markCompletedExternally fallback (context-only). First signal to
        // report completion wins the record — a later, slower channel (say,
        // a delayed notification reply arriving after the watch already
        // logged it) must not overwrite *when* or *how* the task actually
        // got done, or silently reclassify a fast response as a late one.
        private suspend fun applyAuditFulfillment(db: VitalityDatabase, protocolId: Int, interactionType: String) {
            val audit = db.systemDao().getAuditByNotificationId(protocolId) ?: return
            val now = System.currentTimeMillis()
            val fulfilledAt = audit.timestampFulfilled ?: now
            val timeDelta = (fulfilledAt - audit.timestampIssued) / 1000
            val finalStat = if (timeDelta > 3600) "CRITICAL_DELAY" else "SUCCESS"
            db.systemDao().updateAudit(
                audit.copy(
                    timestampInteracted = audit.timestampInteracted ?: now,
                    timestampFulfilled = fulfilledAt,
                    interactionType = audit.interactionType ?: interactionType,
                    finalStatus = audit.finalStatus ?: finalStat
                )
            )
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
            userContextPhotoUri = null
        }
    }

    // Quick-purge, surgical-deletion, factory-reset, and fuzzy-data-injection
    // now live in debug/DebugActions.kt, gated behind DebugFlags — see
    // debug/DebugTools.kt's DebugPanel for the UI.
}