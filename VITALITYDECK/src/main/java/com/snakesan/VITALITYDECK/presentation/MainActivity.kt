package com.snakesan.vitalitysys

import android.content.BroadcastReceiver
import com.google.android.gms.wearable.PutDataMapRequest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material.MaterialTheme
import androidx.work.*
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.snakesan.vitalitysys.debug.clearDebugBleed
import com.snakesan.vitalitysys.debug.debugBleedDamage
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener, DataClient.OnDataChangedListener {

    var activeDeckIndex by mutableIntStateOf(0)
    var nutrientCount by mutableIntStateOf(0)
    var hydrationCount by mutableIntStateOf(0)
    // Completed dose/hygiene-task keys for today (see SysConfig.doseKey /
    // hygieneKey). Replaces the old single medsTaken/maintenanceDone
    // booleans now that Chemistry and Maintenance can each hold any number
    // of user-defined reminders.
    var completedKeys by mutableStateOf<Set<String>>(emptySet())
    var config by mutableStateOf(SysConfig.DEFAULT)
    var syncState by mutableStateOf(SyncState.HIDDEN)

    // Overcharge vals
    var overchargeStartTime = 0L
    var currentOvercharge = 0

    lateinit var store: VitalityStore

    // --- KILL SWITCH RECEIVER ---
    private val killReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.snakesan.overseer.KILL_COMMAND") {
                android.util.Log.w("VITALITY_SYS", ">>> KILL COMMAND RECEIVED. GOING DARK. <<<")
                
                // 1. Cancel Sentinel (Stop wakeups)
                WorkManager.getInstance(context).cancelAllWork()
                
                // 2. Detach Listeners
                try {
                    Wearable.getMessageClient(context).removeListener(this@MainActivity)
                } catch (e: Exception) { /* Ignore */ }

                // 3. Kill UI and Process
                finishAndRemoveTask()
                exitProcess(0)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = VitalityStore(this)
        store.checkDailyReset()
        loadState()
        Wearable.getDataClient(this).addListener(this)

        // REGISTER KILL SWITCH
        val filter = IntentFilter("com.snakesan.overseer.KILL_COMMAND")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(killReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(killReceiver, filter)
        }

        Wearable.getMessageClient(this).addListener(this)
        val workRequest = PeriodicWorkRequestBuilder<SentinelWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("VitalitySentinel", ExistingPeriodicWorkPolicy.KEEP, workRequest)
        
        flushPainLogs()
        flushPendingTelemetry()
        // Signal Watch Face on startup to ensure sync
        broadcastToOverseerLocal()

        lifecycleScope.launch {
            // repeatOnLifecycle suspends this while the watch face/app is
            // backgrounded and resumes when it's active again — without it
            // this loop kept ticking (compute + Data Layer send) every
            // minute indefinitely, draining battery on the watch even with
            // the screen off, redundant with the 15-minute SentinelWorker.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    // Run the math and broadcast to the Watch Face
                    broadcastToOverseerLocal()

                    // Delay for 1 minute (60,000 ms) before calculating the next DoT tick
                    // This provides a smooth, consistent drop in HP without draining the battery
                    delay(60000L)
                }
            }
        }

        setContent { 
            MaterialTheme { 
                androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                    VitalityDeckUI(this@MainActivity)
                    SyncOverlay(syncState)
                }
            } 
        }
    }

    private fun loadState() {
        nutrientCount = store.nutrientCount
        hydrationCount = store.hydrationCount
        completedKeys = store.completedKeys
        config = store.getConfig()
    }

    override fun onResume() {
        super.onResume()
        store.checkDailyReset()
        loadState()
        flushPainLogs()
        flushPendingTelemetry()
        broadcastToOverseerLocal()
    }

    // Automagically sync
    private fun pushStateToDataLayer() {
        val putDataReq = PutDataMapRequest.create("/vitality_state").apply {
            dataMap.putInt("nutrients", store.nutrientCount)
            dataMap.putInt("hydration", store.hydrationCount)
            dataMap.putString("completedKeys", encodeKeySet(store.completedKeys))
            dataMap.putLong("timestamp", System.currentTimeMillis()) // Force sync
        }.asPutDataRequest()

        putDataReq.setUrgent()
        Wearable.getDataClient(this).putDataItem(putDataReq)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == "/vitality_config") {
                val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                val json = map.getString("config_json")
                val newConfig = json?.let {
                    try { SysConfig.fromJson(org.json.JSONObject(it)) } catch (e: Exception) { null }
                } ?: return@forEach

                store.saveConfig(newConfig)
                config = newConfig

                // Re-evaluate HP based on new schedule
                broadcastToOverseerLocal()

                // Give user physical feedback that config updated
                vibrateAck(this@MainActivity, heavy = true)
            }
        }
    }
    
    override fun onDestroy() { 
        super.onDestroy()
        Wearable.getMessageClient(this).removeListener(this)
        Wearable.getDataClient(this).removeListener(this)
        try {
            unregisterReceiver(killReceiver)
        } catch (e: Exception) { /* Receiver might not be registered */ }
    }

    // --- UNIFIED BROADCAST PROTOCOL (UPDATED) ---
    // Replace the old broadcastToOverseerLocal() with this:
    // internal (not private) so debug/DebugTools.kt's extension functions
    // can force a rebroadcast after a debug-only state change.
    internal fun broadcastToOverseerLocal() {
        val payload = VitalityMath.calculateSystemStatus(
            nutrientCount = nutrientCount, hydrationCount = hydrationCount,
            completedKeys = completedKeys, config = config
        )

        // Any HP hit from a manually-forced debug alert (see debug/DebugTools.kt)
        val totalDebugDmg = debugBleedDamage()

        val finalHp = (payload.hp - totalDebugDmg).coerceIn(0, 100)

        if (finalHp == 100) {
            if (overchargeStartTime == 0L) overchargeStartTime = System.currentTimeMillis()
            val elapsedMins = (System.currentTimeMillis() - overchargeStartTime) / 60000
            currentOvercharge = ((elapsedMins * 50) / 60).toInt().coerceIn(0, 50)
        } else {
            overchargeStartTime = 0L
            currentOvercharge = 0
        }

        val intent = Intent("com.snakesan.overseer.UPDATE_STATUS").apply {
            setPackage("com.snakesan.overseer")
            putExtra("source_app", "VITALITY")
            putExtra("hp", finalHp)
            putExtra("hyd_status", payload.hydStatus)
            putExtra("meal_status", payload.mealStatus)
            putExtra("overcharge", currentOvercharge)
        }

        sendBroadcast(intent)
        pushStateToDataLayer()
    }




    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == "/sys/sync_start") {
            lifecycleScope.launch {
                syncState = SyncState.CONNECTING_RX; vibrateAck(this@MainActivity, heavy = true)
                try { 
                    val newConfig = SysConfig.fromBytes(event.data)
                    store.saveConfig(newConfig)
                    config = newConfig 
                    broadcastToOverseerLocal() 
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
        if (event.path == "/sys/sync_animate") {
            lifecycleScope.launch {
                syncState = SyncState.RECEIVING; delay(3500); sendTelemetry()
                syncState = SyncState.SUCCESS; vibrateAck(this@MainActivity, heavy = false); delay(1200); syncState = SyncState.HIDDEN
            }
        }
        if (event.path == "/sys/pull_start") {
            lifecycleScope.launch { 
                syncState = SyncState.CONNECTING_TX; vibrateAck(this@MainActivity, heavy = true); flushPainLogs()
            }
        }
        if (event.path == "/sys/pull_animate") {
            lifecycleScope.launch {
                syncState = SyncState.SENDING; delay(3500); sendTelemetry()
                syncState = SyncState.SUCCESS; vibrateAck(this@MainActivity, heavy = false); delay(1200); syncState = SyncState.HIDDEN
            }
        }
    }

    private suspend fun sendTelemetry() {
        val keysBytes = encodeKeySet(store.completedKeys).toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + 4 + 4 + keysBytes.size)
        buffer.putInt(store.nutrientCount); buffer.putInt(store.hydrationCount)
        buffer.putInt(keysBytes.size); buffer.put(keysBytes)
        withContext(Dispatchers.IO) {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(this@MainActivity).connectedNodes)
                nodes.forEach { node -> Tasks.await(Wearable.getMessageClient(this@MainActivity).sendMessage(node.id, "/sys/sync_response", buffer.array())) }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun logEvent(protocol: Protocol, itemKey: String = "") {
        vibrateAck(this, heavy = true)
        val now = System.currentTimeMillis()

        // CLEAR DEBUG DAMAGE ON HEAL
        clearDebugBleed(protocol.id)

        when(protocol) {
            Protocol.NUTRIENT -> { store.nutrientCount++; nutrientCount = store.nutrientCount }
            Protocol.HYDRATION -> { store.hydrationCount++; hydrationCount = store.hydrationCount }
            Protocol.CHEMISTRY, Protocol.MAINTENANCE -> if (itemKey.isNotEmpty()) {
                store.completedKeys = store.completedKeys + itemKey
                completedKeys = store.completedKeys
            }
        }
        store.setLastTime(protocol, now)

        // broadcastToOverseerLocal() already ends with pushStateToDataLayer(),
        // which is what actually carries the authoritative counts/
        // completedKeys to the phone.
        broadcastToOverseerLocal()

        // Telemetry separately tells the phone *that this specific action
        // happened* — closing out its audit/notification trail and
        // reconciling any in-flight overlay for the same item — so it's
        // queued like pending pain logs rather than fired-and-forgotten:
        // a momentary disconnect right when this button is tapped must not
        // silently drop a real response and leave the phone thinking it was
        // ignored.
        store.addPendingTelemetry(protocol.id, itemKey, now)
        flushPendingTelemetry()
    }

    private fun flushPendingTelemetry() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pending = store.getPendingTelemetry()
            if (pending.isEmpty()) return@launch
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(this@MainActivity).connectedNodes)
                if (nodes.isEmpty()) return@launch
                pending.forEach { (protocolId, itemKey, timestamp) ->
                    val event = TelemetryEvent(protocolId, timestamp, itemKey)
                    nodes.forEach { node ->
                        Tasks.await(Wearable.getMessageClient(this@MainActivity).sendMessage(node.id, "/sys/telemetry", event.toBytes()))
                        delay(50)
                    }
                    // Only drop this one entry once every node has it — same
                    // reasoning as flushPainLogs: a failure partway through
                    // just leaves the rest queued for the next flush.
                    store.removePendingTelemetry(protocolId, itemKey, timestamp)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // forceRunSentinel() moved to debug/DebugTools.kt, gated behind DebugFlags.

    fun logPain(level: Int) {
        vibrateAck(this, heavy = true)
        val now = System.currentTimeMillis()
        store.addPendingPainLog(now, level)
        store.lastPainLevel = level
        flushPainLogs()
        if (level > 3) {
            schedulePainCheck()
        } else {
            WorkManager.getInstance(this).cancelUniqueWork("PainCheck")
        }
        activeDeckIndex = 0
    }
    
    private fun schedulePainCheck() {
        val painCheck = OneTimeWorkRequestBuilder<SentinelWorker>()
            .setInitialDelay(60, TimeUnit.MINUTES)
            .setInputData(Data.Builder().putBoolean("CHECK_PAIN", true).build())
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("PainCheck", ExistingWorkPolicy.REPLACE, painCheck)
    }

    private fun flushPainLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            val logs = store.getPendingPainLogs()
            if (logs.isEmpty()) return@launch
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(this@MainActivity).connectedNodes)
                if (nodes.isEmpty()) return@launch
                logs.forEach { (timestamp, level) ->
                    val buffer = ByteBuffer.allocate(12); buffer.putInt(level); buffer.putLong(timestamp)
                    nodes.forEach { node ->
                        Tasks.await(Wearable.getMessageClient(this@MainActivity).sendMessage(node.id, "/sys/pain_log", buffer.array()))
                        delay(50)
                    }
                    // Only drop this one log once every node has it — if a
                    // send fails partway through, this (and anything after
                    // it) simply stays queued for the next flush, instead of
                    // resending logs that already made it across.
                    store.removePendingPainLog(timestamp, level)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
}
