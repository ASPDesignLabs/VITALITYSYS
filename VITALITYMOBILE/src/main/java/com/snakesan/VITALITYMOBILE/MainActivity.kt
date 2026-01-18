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
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

// --- DATA LAYER ---
enum class Protocol(val id: Int, val label: String, val colorHex: Long) {
    NUTRIENT(0, "NUTRIENT", 0xFF00F3FF),      // Neon Cyan
    CHEMISTRY(1, "CHEMISTRY", 0xFFFF0055),    // Neon Pink
    HYDRATION(2, "HYDRATION", 0xFF00FF41),    // Bio Green
    MAINTENANCE(3, "MAINTENANCE", 0xFFFF9900) // Data Amber
}

data class TelemetryEvent(val protocolId: Int, val timestamp: Long) {
    companion object {
        fun fromBytes(bytes: ByteArray): TelemetryEvent {
            val buffer = ByteBuffer.wrap(bytes)
            return TelemetryEvent(buffer.int, buffer.long)
        }
    }
}

data class SysConfig(
    val meal1Time: Int, val meal2Time: Int, val meal3Time: Int,
    val medsWeekday: Int, val medsWeekend: Int,
    val hydrationTargetMl: Int, val activeStartHour: Int, val activeEndHour: Int,
    val maint1Time: Int, val maint2Time: Int
) {
    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(40)
        buffer.putInt(meal1Time); buffer.putInt(meal2Time); buffer.putInt(meal3Time)
        buffer.putInt(medsWeekday); buffer.putInt(medsWeekend)
        buffer.putInt(hydrationTargetMl); buffer.putInt(activeStartHour); buffer.putInt(activeEndHour)
        buffer.putInt(maint1Time); buffer.putInt(maint2Time)
        return buffer.array()
    }
}

// --- STATE MACHINE ---
enum class AppMode { DASHBOARD, INTERRUPT_CAPTURE, INTERRUPT_ACTION, INTERRUPT_RESTORE }
enum class UploadState { IDLE, UPLOADING, SUCCESS }

// --- THEME ---
val NeonCyan = Color(0xFF00F3FF)
val NeonPink = Color(0xFFFF0055)
val NeonGreen = Color(0xFF00FF41)
val NeonBg = Color(0xFF050505)
val NeonDark = Color(0xFF121212)

class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    // Telemetry State
    var nutrientCount by mutableIntStateOf(0)
    var hydrationCount by mutableIntStateOf(0)
    var medsTaken by mutableStateOf(false)
    var hygieneDone by mutableStateOf(false)
    
    // Configuration State
    var meal1 by mutableFloatStateOf(540f)
    var meal2 by mutableFloatStateOf(780f)
    var meal3 by mutableFloatStateOf(1140f)
    var medsWkday by mutableFloatStateOf(480f)
    var medsWkend by mutableFloatStateOf(600f)
    var hydrationTarget by mutableFloatStateOf(3250f)
    var activeStart by mutableFloatStateOf(8f) 
    var activeEnd by mutableFloatStateOf(22f)

    // UI State
    var uploadState by mutableStateOf(UploadState.IDLE)
    var appMode by mutableStateOf(AppMode.DASHBOARD)
    var activeProtocol by mutableStateOf<Protocol?>(null) // Which protocol triggered the alert
    var userContext by mutableStateOf("") // "What was I doing?"

    // Permission Launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Permission logic handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        Wearable.getMessageClient(this).addListener(this)
        
        createNotificationChannel()
        
        // CHECK INTENT: Did we open via Notification?
        handleIntent(intent)

        // ASK FOR PERMISSION ON LAUNCH
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent { NeonTheme { VitalityOrchestrator(this) } }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
    
    private fun handleIntent(intent: Intent) {
        if (intent.hasExtra("PROTOCOL_ID")) {
            val protoId = intent.getIntExtra("PROTOCOL_ID", -1)
            if (protoId != -1) {
                activeProtocol = Protocol.values().firstOrNull { it.id == protoId }
                if (activeProtocol != null) {
                    appMode = AppMode.INTERRUPT_CAPTURE // Start the Trap
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Wearable.getMessageClient(this).removeListener(this)
    }

    override fun onMessageReceived(event: MessageEvent) {
        // 1. Telemetry Sync
        if (event.path == "/sys/telemetry") {
            val telemetry = TelemetryEvent.fromBytes(event.data)
            when(telemetry.protocolId) {
                0 -> nutrientCount++
                1 -> medsTaken = true
                2 -> hydrationCount++
                3 -> hygieneDone = !hygieneDone
            }
        }
        
        // 2. REMOTE TRIGGER from Watch
        if (event.path == "/sys/alert_phone") {
            val protoId = ByteBuffer.wrap(event.data).int
            val protocol = Protocol.values().firstOrNull { it.id == protoId }
            if (protocol != null) {
                triggerNotification(protocol)
            }
        }
    }
    
    fun sendConfig() {
        val config = SysConfig(
            meal1Time = meal1.toInt(), meal2Time = meal2.toInt(), meal3Time = meal3.toInt(),
            medsWeekday = medsWkday.toInt(), medsWeekend = medsWkend.toInt(),
            hydrationTargetMl = hydrationTarget.toInt(), 
            activeStartHour = activeStart.toInt(), activeEndHour = activeEnd.toInt(),
            maint1Time = 450, maint2Time = 1320
        )
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { Wearable.getMessageClient(this).sendMessage(it.id, "/sys/config", config.toBytes()) }
        }
    }
    
    private fun triggerNotification(protocol: Protocol) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && 
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("PROTOCOL_ID", protocol.id)
        }
        
        // FLAG_IMMUTABLE is required for Android 12+
        val pendingIntent = PendingIntent.getActivity(this, protocol.id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, "vitality_urgent")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("PROTOCOL: ${protocol.label}")
            .setContentText("CRITICAL MAINTENANCE REQUIRED. TAP TO ENGAGE.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setColor(0xFF00F3FF.toInt())
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(this).notify(protocol.id, builder.build())
        } catch (e: SecurityException) {
            // Permission missing
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("vitality_urgent", "Vitality Protocols", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Interrupts for bio-maintenance"
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    @Composable
    fun NeonTheme(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = NeonCyan, secondary = NeonPink, background = NeonBg, surface = NeonDark
            ), content = content
        )
    }
}

// --- UI ORCHESTRATOR ---
@Composable
fun VitalityOrchestrator(activity: MainActivity) {
    Box(Modifier.fillMaxSize().background(NeonBg)) {
        // 1. Standard Dashboard
        VitalityDashboard(activity)

        // 2. Interruption Overlay (Hijacks screen)
        if (activity.appMode != AppMode.DASHBOARD && activity.activeProtocol != null) {
            InterruptionOverlay(activity)
        }
    }
}

@Composable
fun InterruptionOverlay(activity: MainActivity) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NeonBg.copy(alpha = 0.98f)) // Near opaque
            .clickable(enabled = false) {}
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            
            // STAGE 1: CAPTURE
            if (activity.appMode == AppMode.INTERRUPT_CAPTURE) {
                Text("INTERRUPT DETECTED", color = NeonPink, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.height(20.dp))
                Text("STATE CURRENT VECTOR", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text("( What were you doing? )", color = Color.Gray, fontSize = 12.sp)
                
                Spacer(Modifier.height(30.dp))
                
                var text by remember { mutableStateOf("") }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(color = NeonCyan, fontSize = 22.sp, fontWeight = FontWeight.Bold),
                    cursorBrush = SolidColor(NeonCyan),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NeonCyan, CutCornerShape(8.dp))
                        .padding(20.dp)
                )
                
                Spacer(Modifier.height(30.dp))
                
                CyberButtonBlock("LOCK VECTOR") {
                    activity.userContext = text.ifEmpty { "UNKNOWN TASK" }
                    activity.appMode = AppMode.INTERRUPT_ACTION
                }
            }

            // STAGE 2: ACTION
            if (activity.appMode == AppMode.INTERRUPT_ACTION) {
                val protocol = activity.activeProtocol!!
                val color = Color(protocol.colorHex)
                
                Text("VECTOR LOCKED", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(activity.userContext.uppercase(), color = Color.White, fontSize = 14.sp)
                
                Spacer(Modifier.height(60.dp))
                
                Text("EXECUTE PROTOCOL", color = color, fontSize = 12.sp, letterSpacing = 2.sp)
                Text(protocol.label, color = color, fontSize = 40.sp, fontWeight = FontWeight.Black)
                
                Spacer(Modifier.height(60.dp))
                
                CyberButtonBlock("CONFIRM COMPLETION") {
                    // Update state locally
                    when(protocol) {
                        Protocol.NUTRIENT -> activity.nutrientCount++
                        Protocol.CHEMISTRY -> activity.medsTaken = true
                        Protocol.HYDRATION -> activity.hydrationCount++
                        Protocol.MAINTENANCE -> activity.hygieneDone = true
                    }
                    activity.appMode = AppMode.INTERRUPT_RESTORE
                }
            }

            // STAGE 3: RESTORE
            if (activity.appMode == AppMode.INTERRUPT_RESTORE) {
                Text("SYSTEM OPTIMIZED", color = NeonGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                
                Spacer(Modifier.height(40.dp))
                
                Text("RESUMING VECTOR:", color = Color.Gray, fontSize = 12.sp)
                Text(activity.userContext.uppercase(), color = NeonCyan, fontSize = 28.sp, fontWeight = FontWeight.Black)
                
                Spacer(Modifier.height(60.dp))
                
                CyberButtonBlock("ENGAGE") {
                    activity.appMode = AppMode.DASHBOARD
                    activity.activeProtocol = null
                    activity.userContext = ""
                }
            }
        }
    }
}

@Composable
fun VitalityDashboard(activity: MainActivity) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NeonBg)
            .systemBarsPadding()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(20.dp))
        Text("VITALITY.SYS", color = NeonCyan, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
        Text("CLINICAL CONTROLLER", color = Color.Gray, fontSize = 12.sp, letterSpacing = 2.sp)
        
        Spacer(Modifier.height(30.dp))

        // --- CARDS ---
        ProtocolCard(
            protocol = Protocol.NUTRIENT,
            current = activity.nutrientCount, target = 3, unit = "MEALS",
            content = {
                ConfigLabel("INTAKE SCHEDULE")
                TimeSlider("MEAL 1", activity.meal1) { activity.meal1 = it }
                TimeSlider("MEAL 2", activity.meal2) { activity.meal2 = it }
                TimeSlider("MEAL 3", activity.meal3) { activity.meal3 = it }
            }
        )

        ProtocolCard(
            protocol = Protocol.CHEMISTRY,
            current = if(activity.medsTaken) 1 else 0, target = 1, unit = if(activity.medsTaken) "COMPLIANT" else "PENDING",
            content = {
                ConfigLabel("DOSAGE TIMING")
                TimeSlider("WEEKDAY", activity.medsWkday) { activity.medsWkday = it }
                TimeSlider("WEEKEND", activity.medsWkend) { activity.medsWkend = it }
            }
        )

        ProtocolCard(
            protocol = Protocol.HYDRATION,
            current = activity.hydrationCount, target = (activity.hydrationTarget / 250).toInt(), unit = "DOSES",
            content = {
                ConfigLabel("VOLUME & CYCLE")
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text("TARGET: ${activity.hydrationTarget.toInt()}mL", color = Color(Protocol.HYDRATION.colorHex), fontSize = 12.sp)
                }
                Slider(
                    value = activity.hydrationTarget, onValueChange = { activity.hydrationTarget = it },
                    valueRange = 1000f..4000f, steps = 10,
                    colors = SliderDefaults.colors(thumbColor = Color(Protocol.HYDRATION.colorHex), activeTrackColor = Color(Protocol.HYDRATION.colorHex))
                )
                Text("ACTIVE: ${activity.activeStart.toInt()}:00 - ${activity.activeEnd.toInt()}:00", color = Color.Gray, fontSize = 10.sp)
                RangeSlider(
                    value = activity.activeStart..activity.activeEnd,
                    onValueChange = { activity.activeStart = it.start; activity.activeEnd = it.endInclusive },
                    valueRange = 0f..24f,
                    colors = SliderDefaults.colors(thumbColor = Color(Protocol.HYDRATION.colorHex), activeTrackColor = Color(Protocol.HYDRATION.colorHex))
                )
            }
        )

        ProtocolCard(
            protocol = Protocol.MAINTENANCE,
            current = if(activity.hygieneDone) 1 else 0, target = 1, unit = if(activity.hygieneDone) "OPTIMAL" else "DEGRADED",
            content = { Text("HARDCODED: 07:30 // 22:00", color = Color.Gray, fontSize = 10.sp) }
        )
        
        Spacer(Modifier.height(30.dp))
        
        // --- SMART SYNC BUTTON ---
        SmartSyncButton(
            state = activity.uploadState,
            onClick = {
                if (activity.uploadState == UploadState.IDLE) {
                    activity.sendConfig()
                    scope.launch {
                        activity.uploadState = UploadState.UPLOADING
                        delay(2500) 
                        activity.uploadState = UploadState.SUCCESS
                        delay(1000) 
                        activity.uploadState = UploadState.IDLE
                    }
                }
            }
        )
        Spacer(Modifier.height(20.dp))
    }
}

// --- SHARED UI ---
@Composable
fun CyberButtonBlock(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(50.dp).clip(CutCornerShape(8.dp))
            .background(NeonCyan.copy(alpha = 0.1f)).border(1.dp, NeonCyan.copy(alpha = 0.5f), CutCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = NeonCyan, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
fun SmartSyncButton(state: UploadState, onClick: () -> Unit) {
    val progress by animateFloatAsState(targetValue = if (state == UploadState.UPLOADING) 1f else 0f, animationSpec = tween(2500, easing = LinearEasing))
    val containerColor by animateColorAsState(targetValue = if(state == UploadState.SUCCESS) NeonGreen else NeonDark)
    val borderColor = if (state == UploadState.IDLE) NeonCyan else Color.Transparent
    val textColor = if (state == UploadState.SUCCESS) Color.Black else NeonCyan

    Box(
        modifier = Modifier.fillMaxWidth().height(60.dp).clip(CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp))
            .background(containerColor).border(2.dp, borderColor, CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp))
            .clickable(onClick = onClick)
    ) {
        if (state == UploadState.UPLOADING) Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Color(0xFFFF9900)))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = when(state) {
                UploadState.IDLE -> "UPLOAD CONFIGURATION"
                UploadState.UPLOADING -> "UPLINKING [${(progress*100).toInt()}%]..."
                UploadState.SUCCESS -> "SYNC COMPLETE"
            }, color = textColor, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }
    }
}

@Composable
fun ProtocolCard(protocol: Protocol, current: Int, target: Int, unit: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val color = Color(protocol.colorHex)
    val progress = (current.toFloat() / target.toFloat()).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(targetValue = progress)

    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
            .background(color.copy(alpha = 0.05f)).border(1.dp, if(expanded) color else Color.DarkGray, CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column {
                    Text(protocol.label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    Text("$current / $target $unit", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.width(60.dp).height(6.dp).background(Color.Black)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(animatedProgress).background(color))
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 16.dp)) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.DarkGray))
                    Spacer(Modifier.height(16.dp))
                    content()
                }
            }
        }
    }
}

@Composable
fun TimeSlider(label: String, minutesVal: Float, onValueChange: (Float) -> Unit) {
    val hour = (minutesVal / 60).toInt()
    val min = (minutesVal % 60).toInt()
    val timeStr = String.format("%02d:%02d", hour, min)
    Column {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(timeStr, color = NeonCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = minutesVal, onValueChange = onValueChange, valueRange = 0f..1439f, 
            colors = SliderDefaults.colors(thumbColor = Color.LightGray, activeTrackColor = Color.DarkGray)
        )
    }
}

@Composable
fun ConfigLabel(text: String) {
    Text(text, color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 10.dp))
}
