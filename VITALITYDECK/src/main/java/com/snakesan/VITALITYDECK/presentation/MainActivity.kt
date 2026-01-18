package com.snakesan.vitalitysys

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.wear.compose.material.*
import androidx.work.*
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import java.nio.ByteBuffer
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// --- DATA LAYER ---
enum class Protocol(val id: Int, val label: String, val colorHex: Long) {
    NUTRIENT(0, "NUTRIENT", 0xFF00F3FF),      
    CHEMISTRY(1, "CHEMISTRY", 0xFFFF0055),    
    HYDRATION(2, "HYDRATION", 0xFF00FF41),    
    MAINTENANCE(3, "MAINTENANCE", 0xFFFF9900) 
}

data class TelemetryEvent(val protocolId: Int, val timestamp: Long) {
    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(12)
        buffer.putInt(protocolId)
        buffer.putLong(timestamp)
        return buffer.array()
    }
}

data class SysConfig(
    val meal1Time: Int, val meal2Time: Int, val meal3Time: Int,
    val medsWeekday: Int, val medsWeekend: Int,
    val hydrationTargetMl: Int, val activeStartHour: Int, val activeEndHour: Int,
    val maint1Time: Int, val maint2Time: Int
) {
    companion object {
        val DEFAULT = SysConfig(540, 780, 1140, 480, 600, 3250, 8, 22, 450, 1320)

        fun fromBytes(bytes: ByteArray): SysConfig {
            if (bytes.isEmpty()) return DEFAULT
            val buffer = ByteBuffer.wrap(bytes)
            return SysConfig(
                buffer.int, buffer.int, buffer.int,
                buffer.int, buffer.int,
                buffer.int, buffer.int, buffer.int,
                buffer.int, buffer.int
            )
        }
    }
}

// --- PERSISTENCE LAYER ---
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

    fun setLastTime(protocol: Protocol, time: Long) = prefs.edit().putLong("last_time_${protocol.id}", time).apply()
    fun getLastTime(protocol: Protocol): Long = prefs.getLong("last_time_${protocol.id}", 0L)

    fun saveConfig(c: SysConfig) {
        prefs.edit()
            .putInt("conf_m1", c.meal1Time).putInt("conf_m2", c.meal2Time).putInt("conf_m3", c.meal3Time)
            .putInt("conf_md_w", c.medsWeekday).putInt("conf_md_e", c.medsWeekend)
            .putInt("conf_hy_t", c.hydrationTargetMl).putInt("conf_hy_s", c.activeStartHour).putInt("conf_hy_e", c.activeEndHour)
            .putInt("conf_mt_1", c.maint1Time).putInt("conf_mt_2", c.maint2Time)
            .apply()
    }

    fun getConfig(): SysConfig {
        if (!prefs.contains("conf_m1")) return SysConfig.DEFAULT
        return SysConfig(
            prefs.getInt("conf_m1", 540), prefs.getInt("conf_m2", 780), prefs.getInt("conf_m3", 1140),
            prefs.getInt("conf_md_w", 480), prefs.getInt("conf_md_e", 600),
            prefs.getInt("conf_hy_t", 3250), prefs.getInt("conf_hy_s", 8), prefs.getInt("conf_hy_e", 22),
            prefs.getInt("conf_mt_1", 450), prefs.getInt("conf_mt_2", 1320)
        )
    }
}

// --- THEME ---
val VitalityBg = Color(0xFF050505)

class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    var activeDeckIndex by mutableIntStateOf(0)
    
    var nutrientCount by mutableIntStateOf(0)
    var hydrationCount by mutableIntStateOf(0)
    var medsTaken by mutableStateOf(false)
    var maintenanceDone by mutableStateOf(false)
    var config by mutableStateOf(SysConfig.DEFAULT)

    private lateinit var store: VitalityStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        store = VitalityStore(this)
        store.checkDailyReset()
        loadState()

        Wearable.getMessageClient(this).addListener(this)
        
        // Periodic Sentinel
        val workRequest = PeriodicWorkRequestBuilder<SentinelWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("VitalitySentinel", ExistingPeriodicWorkPolicy.KEEP, workRequest)
        
        setContent { MaterialTheme { VitalityDeckUI(this) } }
    }

    private fun loadState() {
        nutrientCount = store.nutrientCount
        hydrationCount = store.hydrationCount
        medsTaken = store.medsTaken
        maintenanceDone = store.maintDone
        config = store.getConfig()
    }

    override fun onResume() {
        super.onResume()
        store.checkDailyReset()
        loadState()
    }

    override fun onDestroy() {
        super.onDestroy()
        Wearable.getMessageClient(this).removeListener(this)
    }

    override fun onMessageReceived(event: MessageEvent) { 
        if (event.path == "/sys/config") {
            try {
                val newConfig = SysConfig.fromBytes(event.data)
                store.saveConfig(newConfig)
                config = newConfig
                vibrateAck(this, heavy = false)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun logEvent(protocol: Protocol) {
        vibrateAck(this, heavy = true)
        val now = System.currentTimeMillis()
        
        when(protocol) {
            Protocol.NUTRIENT -> { store.nutrientCount++; nutrientCount = store.nutrientCount }
            Protocol.CHEMISTRY -> { store.medsTaken = true; medsTaken = true }
            Protocol.HYDRATION -> { store.hydrationCount++; hydrationCount = store.hydrationCount }
            Protocol.MAINTENANCE -> { store.maintDone = !store.maintDone; maintenanceDone = store.maintDone }
        }
        store.setLastTime(protocol, now)

        val event = TelemetryEvent(protocol.id, now)
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { Wearable.getMessageClient(this).sendMessage(it.id, "/sys/telemetry", event.toBytes()) }
        }
    }
    
    // --- FORCE DEBUG TRIGGER (UPDATED) ---
    fun forceRunSentinel(debugProtocol: Protocol) {
        vibrateAck(this, heavy = true)
        // Pass a flag AND the protocol we want to test
        val data = Data.Builder()
            .putBoolean("IS_DEBUG", true)
            .putInt("DEBUG_PROTO", debugProtocol.id)
            .build()
            
        val workRequest = OneTimeWorkRequestBuilder<SentinelWorker>()
            .setInputData(data)
            .build()
        WorkManager.getInstance(this).enqueue(workRequest)
    }
}

// --- SENTINEL WORKER ---
class SentinelWorker(val context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        
        // 0. CHECK DEBUG FLAG (UPDATED)
        val isDebug = inputData.getBoolean("IS_DEBUG", false)
        if (isDebug) {
            val protoId = inputData.getInt("DEBUG_PROTO", 2) // Default to Hydration
            val debugProto = Protocol.values().firstOrNull { it.id == protoId } ?: Protocol.HYDRATION
            
            // Generate Flavor Text based on the protocol being tested
            val msg = when(debugProto) {
                Protocol.NUTRIENT -> "DEBUG: Fuel cells empty. Intake required."
                Protocol.CHEMISTRY -> "DEBUG: Chemistry imbalance. Dose required."
                Protocol.HYDRATION -> "DEBUG: Hydration critical. You're drifting."
                Protocol.MAINTENANCE -> "DEBUG: System reset required. Wash up."
            }
            
            triggerAlert(debugProto, msg)
            return Result.success()
        }

        val store = VitalityStore(context)
        store.checkDailyReset()
        val config = store.getConfig()
        
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val dayOfWeek = now.get(Calendar.DAY_OF_WEEK)
        val currentTimeMs = System.currentTimeMillis()

        // 1. NUTRIENT CHECK
        val lastEat = store.getLastTime(Protocol.NUTRIENT)
        if (currentTimeMs - lastEat > 7200000) { 
            checkSchedule(config.meal1Time, currentMinutes, 45, Protocol.NUTRIENT, "Fuel cells empty. Intake required.")
            checkSchedule(config.meal2Time, currentMinutes, 45, Protocol.NUTRIENT, "Systems flagging. Refuel.")
            checkSchedule(config.meal3Time, currentMinutes, 45, Protocol.NUTRIENT, "Running on fumes? Eat.")
        }

        // 2. MEDS CHECK
        val isWeekend = (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY)
        val targetMeds = if (isWeekend) config.medsWeekend else config.medsWeekday
        if (!store.medsTaken) {
            checkSchedule(targetMeds, currentMinutes, 60, Protocol.CHEMISTRY, "Chemistry imbalance detected. Dose required.")
        }

        // 3. MAINTENANCE CHECK
        if (!store.maintDone) {
            checkSchedule(config.maint1Time, currentMinutes, 60, Protocol.MAINTENANCE, "Hygiene check required.")
            checkSchedule(config.maint2Time, currentMinutes, 60, Protocol.MAINTENANCE, "System reset required.")
        }

        // 4. HYDRATION CHECK
        checkHydrationDrift(config, currentMinutes, store.hydrationCount)

        return Result.success()
    }

    private fun checkSchedule(targetTime: Int, currentTime: Int, tolerance: Int, protocol: Protocol, msg: String) {
        val diff = currentTime - targetTime
        if (diff > 0 && diff < (tolerance + 15)) {
             triggerAlert(protocol, msg)
        }
    }

    private fun checkHydrationDrift(config: SysConfig, currentMinutes: Int, currentCount: Int) {
        val startMins = config.activeStartHour * 60
        val endMins = config.activeEndHour * 60
        
        if (currentMinutes in startMins..endMins) {
            val totalActiveDuration = endMins - startMins
            val elapsedActive = currentMinutes - startMins
            val expectedProgress = elapsedActive.toFloat() / totalActiveDuration.toFloat()
            val expectedMl = config.hydrationTargetMl * expectedProgress
            
            val currentMl = currentCount * 250
            val deficit = expectedMl - currentMl
            val driftPercentage = if(expectedMl > 0) deficit / expectedMl else 0f

            if (driftPercentage > 0.20) {
                triggerAlert(Protocol.HYDRATION, "Hydration critical. You're drifting, Samurai.")
            }
        }
    }

    private fun triggerAlert(protocol: Protocol, message: String) {
        val channelId = "vitality_ai"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Vitality AI", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "System Alerts"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("VITALITY.SYS")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        nm.notify(protocol.id, notification)
        
        // REMOTE TRIGGER
        val buffer = ByteBuffer.allocate(4)
        buffer.putInt(protocol.id)
        
        try {
            val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
            nodes.forEach { node -> 
                Tasks.await(Wearable.getMessageClient(context).sendMessage(node.id, "/sys/alert_phone", buffer.array()))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50, 50, 100), -1))
        } else {
            v.vibrate(500)
        }
    }
}

// --- UI COMPONENTS ---

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VitalityDeckUI(activity: MainActivity) {
    val currentProtocol = Protocol.values()[activity.activeDeckIndex]
    val themeColor = Color(currentProtocol.colorHex)
    val focusRequester = remember { FocusRequester() }
    var scrollAccumulator by remember { mutableFloatStateOf(0f) }
    
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VitalityBg)
            .onRotaryScrollEvent {
                scrollAccumulator += it.verticalScrollPixels
                if (abs(scrollAccumulator) > 60f) {
                    val delta = if (scrollAccumulator > 0) 1 else -1
                    var newIndex = (activity.activeDeckIndex + delta) % 4
                    if (newIndex < 0) newIndex += 4 
                    
                    activity.activeDeckIndex = newIndex
                    vibrateAck(activity, heavy = false)
                    scrollAccumulator = 0f
                    true
                } else false
            }
            .focusRequester(focusRequester)
            .focusable()
    ) {
        Text(
            text = "PROT: ${currentProtocol.label}",
            color = themeColor,
            fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when(currentProtocol) {
                Protocol.NUTRIENT -> BigDataDisplay("INTAKE LOG", "${activity.nutrientCount}", "MEALS", themeColor)
                Protocol.CHEMISTRY -> {
                    val status = if(activity.medsTaken) "COMPLIANT" else "REQUIRED"
                    val subColor = if(activity.medsTaken) Color(0xFF00FF41) else themeColor
                    BigDataDisplay("DOSE STATUS", status, "", subColor)
                }
                Protocol.HYDRATION -> {
                    HydrationMonitor(
                        currentMl = activity.hydrationCount * 250,
                        targetMl = activity.config.hydrationTargetMl,
                        activeStartHour = activity.config.activeStartHour,
                        activeEndHour = activity.config.activeEndHour,
                        color = themeColor
                    )
                }
                Protocol.MAINTENANCE -> {
                    val status = if(activity.maintenanceDone) "OPTIMAL" else "DEGRADED"
                    val subColor = if(activity.maintenanceDone) Color(0xFF00FF41) else themeColor
                    BigDataDisplay("HYGIENE SYS", status, "", subColor)
                }
            }
            Spacer(Modifier.height(15.dp))
            
            // LONG CLICK = DEBUG TEST (Now Context Aware)
            CyberButton(
                text = "LOG ENTRY", 
                color = themeColor, 
                onClick = { activity.logEvent(currentProtocol) },
                onLongClick = { activity.forceRunSentinel(currentProtocol) }
            )
        }
        
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(4) { index ->
                Box(modifier = Modifier.size(4.dp).background(if (index == activity.activeDeckIndex) themeColor else Color.DarkGray))
            }
        }
    }
}

@Composable
fun HydrationMonitor(currentMl: Int, targetMl: Int, activeStartHour: Int, activeEndHour: Int, color: Color) {
    val now = Calendar.getInstance()
    val currentMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    val startMins = activeStartHour * 60
    val endMins = activeEndHour * 60
    
    val timeProgress = if(currentMins < startMins) 0f else ((currentMins - startMins).toFloat() / (endMins - startMins).toFloat()).coerceIn(0f, 1f)
    val actualProgress = (currentMl.toFloat() / targetMl.toFloat()).coerceIn(0f, 1f)
    val isDrifting = timeProgress > actualProgress + 0.2f

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("FLUID LEVELS", color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text("$currentMl mL", color = color, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(8.dp))
        
        Box(
            modifier = Modifier
                .width(100.dp)
                .height(8.dp)
                .clip(CutCornerShape(2.dp))
                .background(Color.DarkGray)
        ) {
            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(timeProgress).background(color.copy(alpha = 0.3f)))
            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(actualProgress).background(color))
            if (isDrifting) Box(modifier = Modifier.align(Alignment.CenterEnd).width(4.dp).fillMaxHeight().background(Color.Red))
        }
        
        if (isDrifting) {
             Text("DRIFT DETECTED", color = Color.Red, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun BigDataDisplay(label: String, value: String, unit: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = color, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
            if (unit.isNotEmpty()) {
                Text(unit, color = color.copy(alpha=0.7f), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 5.dp, start = 2.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CyberButton(text: String, color: Color, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.5f), CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp))
    }
}

fun vibrateAck(context: Context, heavy: Boolean) {
    val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val effect = if (heavy) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_CLICK
        v.vibrate(VibrationEffect.createPredefined(effect))
    } else {
        v.vibrate(if (heavy) 100 else 50)
    }
}
