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
import androidx.lifecycle.lifecycleScope
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

    val activeDebugBleeds = mutableMapOf<Int, Long>()

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
        // Signal Watch Face on startup to ensure sync
        broadcastToOverseerLocal()

        lifecycleScope.launch {
            while (isActive) {
                // Run the math and broadcast to the Watch Face
                broadcastToOverseerLocal()

                // Delay for 1 minute (60,000 ms) before calculating the next DoT tick
                // This provides a smooth, consistent drop in HP without draining the battery
                delay(60000L)
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
    private fun broadcastToOverseerLocal() {
        val payload = VitalityMath.calculateSystemStatus(
            nutrientCount = nutrientCount, hydrationCount = hydrationCount,
            completedKeys = completedKeys, config = config
        )

        // Calculate live DoT for any active debug triggers
        var totalDebugDmg = 0
        activeDebugBleeds.forEach { (_, startTime) ->
            val elapsedMins = (System.currentTimeMillis() - startTime) / 60000
            // Instantly hits for 25, then bleeds 1 HP every minute
            totalDebugDmg += 25 + elapsedMins.toInt()
        }

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
        if (event.path == "/sys/debug_overcharge") {
            runOnUiThread {
                // 1. Step the watch's internal clock backward by 30 minutes
                if (overchargeStartTime == 0L) overchargeStartTime = System.currentTimeMillis()
                overchargeStartTime -= (30 * 60 * 1000L)

                // 2. Force the watch to instantly recalculate and broadcast the new Overcharge
                // to OVERSEER without waiting for the 1-minute ticker
                broadcastToOverseerLocal()
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
        activeDebugBleeds.remove(protocol.id)

        when(protocol) {
            Protocol.NUTRIENT -> { store.nutrientCount++; nutrientCount = store.nutrientCount }
            Protocol.HYDRATION -> { store.hydrationCount++; hydrationCount = store.hydrationCount }
            Protocol.CHEMISTRY, Protocol.MAINTENANCE -> if (itemKey.isNotEmpty()) {
                store.completedKeys = store.completedKeys + itemKey
                completedKeys = store.completedKeys
            }
        }
        store.setLastTime(protocol, now)

        broadcastToOverseerLocal()
        pushStateToDataLayer()


        val event = TelemetryEvent(protocol.id, now, itemKey)
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes -> nodes.forEach { Wearable.getMessageClient(this).sendMessage(it.id, "/sys/telemetry", event.toBytes()) } }
    }

    fun forceRunSentinel(debugProtocol: Protocol) {
        vibrateAck(this, heavy = true)

        // Start the bleed clock for this specific protocol
        activeDebugBleeds[debugProtocol.id] = System.currentTimeMillis()

        // Instantly calculate and broadcast the first tick
        broadcastToOverseerLocal()

        val data = Data.Builder().putBoolean("IS_DEBUG", true).putInt("DEBUG_PROTO", debugProtocol.id).build()
        val workRequest = OneTimeWorkRequestBuilder<SentinelWorker>().setInputData(data).build()
        WorkManager.getInstance(this).enqueue(workRequest)
    }

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
                nodes.forEach { node ->
                    logs.forEach { (timestamp, level) ->
                        val buffer = ByteBuffer.allocate(12); buffer.putInt(level); buffer.putLong(timestamp)
                        Tasks.await(Wearable.getMessageClient(this@MainActivity).sendMessage(node.id, "/sys/pain_log", buffer.array()))
                        delay(50) 
                    }
                }
                store.clearPendingPainLogs()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
}
