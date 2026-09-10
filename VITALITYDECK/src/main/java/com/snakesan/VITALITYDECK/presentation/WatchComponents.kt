package com.snakesan.vitalitysys

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.snakesan.vitalitysys.debug.DebugFlags
import com.snakesan.vitalitysys.debug.forceRunSentinel
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.abs

// --- MAIN WATCH UI ---

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VitalityDeckUI(activity: MainActivity) {
    val isPainMode = activity.activeDeckIndex == 4
    // Determine Theme Color
    val themeColor = if (isPainMode) NeonPink else Color(Protocol.values()[activity.activeDeckIndex].colorHex)
    
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope() 
    var scrollAccumulator by remember { mutableFloatStateOf(0f) }
    
    // Pain State Logic
    var painSelection by remember { mutableIntStateOf(5) }
    var isPainInputActive by remember { mutableStateOf(false) } 

    // Animation for "Edge Actuators" when tapped
    val topEdgeAlpha = remember { Animatable(0f) }
    val bottomEdgeAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    
    // Reset pain state if we leave the deck
    LaunchedEffect(activity.activeDeckIndex) {
        if (!isPainMode) {
            isPainInputActive = false
            painSelection = 5
        }
    }

    // --- NAVIGATION HELPER ---
    fun navigate(delta: Int) {
        if (isPainInputActive) {
            // Pain Value Adjustment (0-10)
            // Delta +1 means ADD pain, Delta -1 means SUBTRACT pain
            painSelection = (painSelection + delta).coerceIn(0, 10)
        } else {
            // Standard Deck Navigation
            // Delta +1 means NEXT deck, Delta -1 means PREVIOUS deck
            var newIndex = (activity.activeDeckIndex + delta) % 5 
            if (newIndex < 0) newIndex += 5
            activity.activeDeckIndex = newIndex
        }
        vibrateAck(activity, heavy = false)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VitalityBg)
            .onRotaryScrollEvent {
                scrollAccumulator += it.verticalScrollPixels
                val threshold = if(isPainInputActive) 60f else 40f
                
                if (abs(scrollAccumulator) > threshold) {
                    val delta = if (scrollAccumulator > 0) 1 else -1
                    navigate(delta) // Standard rotary direction
                    scrollAccumulator = 0f
                    true
                } else false
            }
            .focusRequester(focusRequester)
            .focusable()
    ) {
        // --- 1. SCANLINE EFFECT ---
        ScanlineOverlay(themeColor)

        // --- 3. MAIN CONTENT AREA ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 35.dp), // Padding to avoid edge buttons
            contentAlignment = Alignment.Center
        ) {
            if (isPainMode) {
                PainInterface(
                    isActive = isPainInputActive,
                    selection = painSelection,
                    color = themeColor,
                    onActivate = { isPainInputActive = true; vibrateAck(activity, true) },
                    onConfirm = { activity.logPain(painSelection) }
                )
            } else {
                StandardProtocolInterface(
                    protocol = Protocol.values()[activity.activeDeckIndex],
                    activity = activity,
                    color = themeColor
                )
            }
        }
        
        // --- 4. TOUCH NAVIGATION ZONES (THE "ACTUATORS") ---
        
        // TOP ZONE (Previous / Increment)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .align(Alignment.TopCenter)
                .clickable {
                    navigate(-1) // REVERSED: Top now goes BACK/DOWN
                    coroutineScope.launch { 
                        topEdgeAlpha.snapTo(1f); topEdgeAlpha.animateTo(0f, animationSpec = tween(300)) 
                    }
                }
        ) {
            // Visual Feedback for Top
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(themeColor.copy(alpha = 0.3f), Color.Transparent))
            ).alpha(topEdgeAlpha.value))
            
            // Chevrons
            Canvas(modifier = Modifier.align(Alignment.TopCenter).size(20.dp).padding(top=4.dp).alpha(0.5f)) {
                drawLine(themeColor, start = Offset(0f, size.height), end = Offset(size.width/2, 0f), strokeWidth = 3f)
                drawLine(themeColor, start = Offset(size.width/2, 0f), end = Offset(size.width, size.height), strokeWidth = 3f)
            }
        }

        // BOTTOM ZONE (Next / Decrement)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .align(Alignment.BottomCenter)
                .clickable {
                    navigate(1) // REVERSED: Bottom now goes FORWARD/UP
                    coroutineScope.launch { 
                        bottomEdgeAlpha.snapTo(1f); bottomEdgeAlpha.animateTo(0f, animationSpec = tween(300)) 
                    }
                }
        ) {
            // Visual Feedback for Bottom
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, themeColor.copy(alpha = 0.3f)))
            ).alpha(bottomEdgeAlpha.value))

            // Chevrons
            Canvas(modifier = Modifier.align(Alignment.BottomCenter).size(20.dp).padding(bottom=4.dp).alpha(0.5f)) {
                drawLine(themeColor, start = Offset(0f, 0f), end = Offset(size.width/2, size.height), strokeWidth = 3f)
                drawLine(themeColor, start = Offset(size.width/2, size.height), end = Offset(size.width, 0f), strokeWidth = 3f)
            }
        }

        // --- 2. HEADER ---
        // Drawn after the top/bottom navigation zones so its own long-press
        // wins hit-testing over theirs within its small bounds, while the
        // rest of the top strip still navigates as before.
        // Long-press toggles debug mode (see debug/DebugFlags.kt) — off by
        // default, so a normal long-press elsewhere (e.g. the log button)
        // never accidentally fires a test alert.
        val debugContext = LocalContext.current
        Text(
            text = if(isPainMode) "DIAGNOSTIC" else "PROT: ${Protocol.values()[activity.activeDeckIndex].label}",
            color = themeColor,
            fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        val nowEnabled = DebugFlags.toggle(debugContext)
                        vibrateAck(activity, heavy = true)
                        Toast.makeText(
                            debugContext,
                            if (nowEnabled) "DEBUG MODE ON" else "DEBUG MODE OFF",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
        )

        // --- 5. PAGINATION DOTS (Right Side) ---
        if (!isPainInputActive) {
            Column(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                repeat(5) { index -> 
                    val isActive = index == activity.activeDeckIndex
                    val dotColor = if (isActive) themeColor else Color.DarkGray
                    Box(modifier = Modifier.size(if(isActive) 5.dp else 4.dp).background(dotColor))
                }
            }
        }
    }
}

// --- SUB-COMPONENTS ---

@Composable
fun ScanlineOverlay(color: Color) {
    val infiniteTransition = rememberInfiniteTransition()
    val yPos by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(4000, easing = LinearEasing))
    )

    Canvas(modifier = Modifier.fillMaxSize().alpha(0.1f)) {
        val lineY = size.height * yPos
        drawLine(
            color = color,
            start = Offset(0f, lineY),
            end = Offset(size.width, lineY),
            strokeWidth = 2f
        )
    }
}

@Composable
fun PainInterface(
    isActive: Boolean, 
    selection: Int, 
    color: Color, 
    onActivate: () -> Unit, 
    onConfirm: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (!isActive) {
            Text("PAIN LOGGING", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            CyberButton(
                text = "INITIATE", 
                color = color, 
                onClick = onActivate,
                onLongClick = {}
            )
        } else {
            Text("TAP EDGES", color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$selection",
                color = if(selection == 0) NeonGreen else if(selection > 7) Color.Red else color,
                fontSize = 48.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = when(selection) {
                    0 -> "RESOLVED"
                    1 -> "NEGLIGIBLE"
                    2,3 -> "NOTICEABLE"
                    4,5 -> "DISTRACTING"
                    6,7 -> "IMPAIRING"
                    8,9 -> "SEVERE"
                    else -> "CRITICAL"
                },
                color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .height(35.dp)
                    .width(100.dp)
                    .clip(CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
                    .background(VitalityBg) 
                    .border(1.dp, color, CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
                    .clickable { onConfirm() },
                contentAlignment = Alignment.Center
            ) {
                Text("CONFIRM", color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun StandardProtocolInterface(protocol: Protocol, activity: MainActivity, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // For Chemistry/Maintenance, show one thing at a time — the single
        // soonest-due pending item — rather than a scrollable list, so the
        // watch face stays a quick glance instead of another list to page
        // through. itemKey is "" when there's nothing left pending today;
        // logEvent() no-ops on an empty key for these two protocols.
        var itemKey = ""
        var buttonLabel = "LOG ENTRY"

        when(protocol) {
            Protocol.NUTRIENT -> BigDataDisplay("INTAKE LOG", "${activity.nutrientCount}", "/ ${activity.config.mealTimes.size}", color)
            Protocol.CHEMISTRY -> {
                val dose = activity.config.nextPendingDose(activity.completedKeys)
                if (dose != null) {
                    itemKey = dose.key
                    BigDataDisplay("NEXT DOSE", dose.medName, formatClock(dose.time), color)
                } else {
                    buttonLabel = "ALL CLEAR"
                    BigDataDisplay("DOSE STATUS", "COMPLIANT", "", NeonGreen)
                }
            }
            Protocol.HYDRATION -> {
                HydrationMonitor(
                    currentMl = activity.hydrationCount * 250,
                    targetMl = activity.config.hydrationTargetMl.toInt(),
                    activeStartHour = activity.config.activeStartHour.toInt(),
                    activeEndHour = activity.config.activeEndHour.toInt(),
                    color = color
                )
            }
            Protocol.MAINTENANCE -> {
                val task = activity.config.nextPendingHygieneTask(activity.completedKeys)
                if (task != null) {
                    itemKey = SysConfig.hygieneKey(task.id)
                    BigDataDisplay("NEXT TASK", task.label, formatClock(task.time), color)
                } else {
                    buttonLabel = "ALL CLEAR"
                    BigDataDisplay("HYGIENE SYS", "OPTIMAL", "", NeonGreen)
                }
            }
        }
        Spacer(Modifier.height(15.dp))
        CyberButton(
            text = buttonLabel,
            color = color,
            onClick = { activity.logEvent(protocol, itemKey) },
            onLongClick = { activity.forceRunSentinel(protocol) }
        )
    }
}

// Formats minutes-since-midnight as "HH:MM" for the watch's next-due-item display.
fun formatClock(minutesSinceMidnight: Int): String {
    val hour = (minutesSinceMidnight / 60) % 24
    val minute = minutesSinceMidnight % 60
    return String.format("%02d:%02d", hour, minute)
}

// --- HELPER COMPOSABLES RETAINED (Just ensuring signatures match) ---

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
        Row(verticalAlignment = Alignment.Bottom) {
             Text("$currentMl", color = color, fontSize = 24.sp, fontWeight = FontWeight.Black)
             Text("mL", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
        }
       
        Spacer(Modifier.height(8.dp))
        
        Box(
            modifier = Modifier.width(100.dp).height(8.dp).clip(CutCornerShape(2.dp)).background(Color.DarkGray)
        ) {
            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(timeProgress).background(color.copy(alpha = 0.3f)))
            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(actualProgress).background(color))
            if (isDrifting) Box(modifier = Modifier.align(Alignment.CenterEnd).width(4.dp).fillMaxHeight().background(Color.Red))
        }
        
        if (isDrifting) Text("DRIFT DETECTED", color = Color.Red, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun BigDataDisplay(label: String, value: String, unit: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = color, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
            if (unit.isNotEmpty()) Text(unit, color = color.copy(alpha=0.7f), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 5.dp, start = 2.dp))
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

// --- UTILITY ---
fun vibrateAck(context: Context, heavy: Boolean) {
    val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val effect = if (heavy) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_CLICK
        v.vibrate(VibrationEffect.createPredefined(effect))
    } else {
        v.vibrate(if (heavy) 100L else 50L)
    }
}
