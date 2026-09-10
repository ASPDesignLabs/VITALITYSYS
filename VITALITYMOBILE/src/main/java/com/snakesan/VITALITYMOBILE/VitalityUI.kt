package com.snakesan.vitalitysys

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.Wearable
import com.snakesan.vitalitysys.data.NotificationAudit
import com.snakesan.vitalitysys.data.SystemLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*


@Composable
fun VitalityOrchestrator(activity: MainActivity) {
    // Fetch last 90 days of logs for local paging
    val logs by activity.db.systemDao().getLogsInRange(
        startTime = System.currentTimeMillis() - (90L * 86400000L),
        endTime = System.currentTimeMillis()
    ).collectAsState(initial = emptyList())

    // NEW: Fetch Audit Trail
    val audits by activity.db.systemDao().getRecentAudits().collectAsState(initial = emptyList())

    Box(Modifier.fillMaxSize().background(NeonBg)) {
        VitalityDashboard(activity, logs, audits)

        if (activity.appMode != AppMode.DASHBOARD && activity.activeProtocol != null) {
            if (activity.appMode == AppMode.NUTRITION_CAPTURE) {
                NutritionOverlay(
                    onLogMeal = { cal, fat, pro, fiber, sugar ->
                        // 1. Launch Health Connect push
                        activity.lifecycleScope.launch(Dispatchers.IO) {
                            activity.healthConnectManager.logMeal(
                                cal.toDouble(),
                                fat.toDouble(),
                                pro.toDouble(),
                                fiber.toDouble(),
                                sugar.toDouble()
                            )
                        }
                        // 2. Fulfill protocol logic
                        activity.fulfillProtocolAudit(Protocol.NUTRIENT.id)
                        activity.nutrientCount++
                        activity.persistState()
                        activity.userContext = "OPTIMIZED NUTRITION VECTOR"
                        activity.appMode = AppMode.INTERRUPT_RESTORE
                    }
                )
            } else {
                InterruptionOverlay(activity)
            }
        }
    }
}

@Composable
fun InterruptionOverlay(activity: MainActivity) {
    val isPainEvent = activity.pendingPainLevel > 0
    val painLevel = activity.pendingPainLevel

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NeonBg.copy(alpha = 0.98f))
            .clickable(enabled = false) {}
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {

            // --- PHASE 1: CAPTURE INPUT ---
            if (activity.appMode == AppMode.INTERRUPT_CAPTURE) {
                Text(
                    text = if (isPainEvent) "PAIN EVENT DETECTED (LVL $painLevel)" else "INTERRUPT DETECTED",
                    color = NeonPink, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                )
                Spacer(Modifier.height(20.dp))

                Text(
                    text = if (isPainEvent) "DESCRIBE SYMPTOMS" else "STATE CURRENT VECTOR",
                    color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black
                )
                Text(
                    text = if (isPainEvent) "( Location / Type / Triggers )" else "( What were you doing? )",
                    color = Color.Gray, fontSize = 12.sp
                )
                Spacer(Modifier.height(30.dp))

                var text by remember { mutableStateOf("") }

                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(color = NeonCyan, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(NeonCyan),
                    modifier = Modifier.fillMaxWidth().border(1.dp, NeonCyan, VitalityShape).padding(20.dp)
                )

                Spacer(Modifier.height(30.dp))

                CyberButtonBlock(if (isPainEvent) "LOG DATA & RESUME" else "LOCK VECTOR") {
                    if (isPainEvent) {
                        activity.commitPainLog(text.ifEmpty { "No details provided" })
                    } else {
                        activity.userContext = text.ifEmpty { "UNKNOWN TASK" }
                        activity.appMode = AppMode.INTERRUPT_ACTION
                    }
                }
            }

            // --- PHASE 2: EXECUTE PROTOCOL ---
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
                    // NEW: Stop the fulfillment clock!
                    activity.fulfillProtocolAudit(protocol.id)

                    when(protocol) {
                        Protocol.NUTRIENT -> { activity.nutrientCount++; activity.persistState() }
                        Protocol.CHEMISTRY, Protocol.MAINTENANCE -> activity.completeItem(activity.pendingItemKey)
                        Protocol.HYDRATION -> {
                            activity.hydrationCount++
                            activity.persistState()
                            activity.lifecycleScope.launch(Dispatchers.IO) {
                                activity.healthConnectManager.logWater(250.0)
                            }
                        }
                    }
                    activity.appMode = AppMode.INTERRUPT_RESTORE
                }
            }

            // --- PHASE 3: RESTORE VECTOR ---
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
fun VitalityDashboard(activity: MainActivity, logs: List<SystemLog>, audits: List<NotificationAudit>) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NeonBg)
            .systemBarsPadding()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- HEADER ---
        Spacer(Modifier.height(4.dp))
        Text("VITALITY.SYS", color = NeonCyan, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
        Text("CLINICAL CONTROLLER", color = Color.Gray, fontSize = 10.sp, letterSpacing = 2.sp)

        Spacer(Modifier.height(10.dp))

        // --- VISUALIZER ---
        Box(modifier = Modifier.height(110.dp)) {
            BioFluxMonitor(
                healthPercentage = activity.currentHP,
                overcharge = activity.currentOvercharge,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        LaunchedEffect(
            activity.mealTimes.toList(), activity.medications.toList(), activity.hygieneTasks.toList(),
            activity.hydrationTarget, activity.activeStart, activity.activeEnd
        ) {
            kotlinx.coroutines.delay(1000)
            activity.saveAndPushConfig()
        }

        Spacer(Modifier.height(12.dp))

        // --- MODULES ---
        DiagnosticModule(logs, audits, activity)

        Spacer(Modifier.height(12.dp))

        val liveConfig = activity.currentConfig()
        val doses = liveConfig.allDoses()
        val completedDoseCount = doses.count { it.key in activity.completedKeys }
        val completedHygieneCount = liveConfig.hygieneTasks.count { SysConfig.hygieneKey(it.id) in activity.completedKeys }

        ProtocolCard(Protocol.NUTRIENT, activity.nutrientCount, activity.mealTimes.size, "MEALS") {
            ConfigLabel("INTAKE SCHEDULE (1-5 MEALS)")
            activity.mealTimes.forEachIndexed { index, time ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        TimeSlider("MEAL ${index + 1}", time.toFloat()) { activity.mealTimes[index] = it.toInt() }
                    }
                    if (activity.mealTimes.size > 1) {
                        Spacer(Modifier.width(8.dp))
                        SmallActionButton("REMOVE", NeonPink) { activity.mealTimes.removeAt(index) }
                    }
                }
            }
            if (activity.mealTimes.size < 5) {
                Spacer(Modifier.height(8.dp))
                SmallActionButton("+ ADD MEAL", NeonCyan, Modifier.fillMaxWidth()) {
                    val lastTime = activity.mealTimes.lastOrNull() ?: 720
                    activity.mealTimes.add((lastTime + 180).coerceAtMost(1439))
                }
            }
        }

        ProtocolCard(Protocol.CHEMISTRY, completedDoseCount, doses.size.coerceAtLeast(1), if (doses.isEmpty()) "NONE SET" else "DOSES") {
            ConfigLabel("MEDICATIONS")
            activity.medications.forEachIndexed { medIndex, med ->
                Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = med.name,
                            onValueChange = { activity.medications[medIndex] = med.copy(name = it) },
                            textStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                            cursorBrush = SolidColor(NeonPink),
                            modifier = Modifier.weight(1f).border(1.dp, Color.DarkGray, VitalityShape).padding(8.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        SmallActionButton("REMOVE MED", NeonPink) { activity.medications.removeAt(medIndex) }
                    }
                    Spacer(Modifier.height(4.dp))
                    med.times.forEachIndexed { timeIndex, time ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                TimeSlider("DOSE ${timeIndex + 1}", time.toFloat()) { newVal ->
                                    val newTimes = med.times.toMutableList().apply { this[timeIndex] = newVal.toInt() }
                                    activity.medications[medIndex] = med.copy(times = newTimes)
                                }
                            }
                            if (med.times.size > 1) {
                                Spacer(Modifier.width(8.dp))
                                SmallActionButton("X", NeonPink) {
                                    val newTimes = med.times.toMutableList().apply { removeAt(timeIndex) }
                                    activity.medications[medIndex] = med.copy(times = newTimes)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    SmallActionButton("+ ADD DOSE TIME", NeonCyan) {
                        val lastTime = med.times.lastOrNull() ?: 480
                        activity.medications[medIndex] = med.copy(times = med.times + (lastTime + 360).coerceAtMost(1439))
                    }
                }
            }
            SmallActionButton("+ ADD MEDICATION", NeonCyan, Modifier.fillMaxWidth()) {
                activity.medications.add(MedicationConfig(id = activity.nextMedId, name = "New Medication", times = listOf(480)))
                activity.nextMedId++
            }
        }

        ProtocolCard(Protocol.HYDRATION, activity.hydrationCount, (activity.hydrationTarget / 250).toInt(), "DOSES") {
            ConfigLabel("VOLUME & CYCLE")
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text("TARGET: ${activity.hydrationTarget.toInt()}mL", color = Color(Protocol.HYDRATION.colorHex), fontSize = 10.sp)
            }

            Slider(
                value = activity.hydrationTarget,
                onValueChange = { activity.hydrationTarget = it },
                valueRange = 1000f..4000f,
                steps = 10,
                colors = SliderDefaults.colors(thumbColor = Color(Protocol.HYDRATION.colorHex), activeTrackColor = Color(Protocol.HYDRATION.colorHex)),
                modifier = Modifier.height(30.dp)
            )

            Text("ACTIVE: ${activity.activeStart.toInt()}:00 - ${activity.activeEnd.toInt()}:00", color = Color.Gray, fontSize = 10.sp)
            RangeSlider(
                value = activity.activeStart..activity.activeEnd,
                onValueChange = { activity.activeStart = it.start; activity.activeEnd = it.endInclusive },
                valueRange = 0f..24f,
                colors = SliderDefaults.colors(thumbColor = Color(Protocol.HYDRATION.colorHex), activeTrackColor = Color(Protocol.HYDRATION.colorHex)),
                modifier = Modifier.height(30.dp)
            )
        }

        ProtocolCard(
            Protocol.MAINTENANCE, completedHygieneCount, liveConfig.hygieneTasks.size.coerceAtLeast(1),
            if (liveConfig.hygieneTasks.isEmpty()) "NONE SET" else "TASKS"
        ) {
            ConfigLabel("HYGIENE TASKS")
            activity.hygieneTasks.forEachIndexed { index, task ->
                Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = task.label,
                            onValueChange = { activity.hygieneTasks[index] = task.copy(label = it) },
                            textStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                            cursorBrush = SolidColor(NeonAmber),
                            modifier = Modifier.weight(1f).border(1.dp, Color.DarkGray, VitalityShape).padding(8.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        SmallActionButton("REMOVE", NeonPink) { activity.hygieneTasks.removeAt(index) }
                    }
                    TimeSlider("TIME", task.time.toFloat()) { activity.hygieneTasks[index] = task.copy(time = it.toInt()) }
                }
            }
            SmallActionButton("+ ADD TASK", NeonCyan, Modifier.fillMaxWidth()) {
                activity.hygieneTasks.add(HygieneTaskConfig(id = activity.nextHygieneId, label = "New Task", time = 480))
                activity.nextHygieneId++
            }
        }

        Spacer(Modifier.weight(1f))

        ComplianceAuditModule(audits)
        DataGovernanceModule(activity)

        Spacer(Modifier.height(20.dp))
    }
}

// --- NEW COMPLIANCE MODULE ---
@Composable
fun ComplianceAuditModule(audits: List<NotificationAudit>) {
    var isRevealed by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(CutCornerShape(bottomEnd = 12.dp))
        )
    }
}

@Composable
fun DiagnosticModule(allLogs: List<SystemLog>, allAudits: List<NotificationAudit>, activity: MainActivity) {
    var isRevealed by remember { mutableStateOf(false) }
    var weekOffset by remember { mutableIntStateOf(0) }
    var isClinicalExport by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    val now = System.currentTimeMillis()
    val oneWeekMs = 604800000L
    val viewEndTime = now - (weekOffset * oneWeekMs)
    val viewStartTime = viewEndTime - oneWeekMs
    val currentViewLogs = allLogs.filter { it.type == "PAIN" && it.timestamp in viewStartTime..viewEndTime }
        .sortedBy { it.timestamp }

    if (showDatePicker) {
        DateRangePickerModal(
            onDateSelected = { start, end ->
                activity.rangeStart = start ?: activity.rangeStart
                activity.rangeEnd = end ?: activity.rangeEnd
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false }
        )
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(CutCornerShape(bottomEnd = 12.dp))
                .background(if (isRevealed) NeonPink.copy(alpha = 0.2f) else Color.DarkGray.copy(alpha = 0.3f))
                .clickable { isRevealed = !isRevealed },
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = if (isRevealed) "DIAGNOSTICS // ACCESSING_ARCHIVE" else "DIAGNOSTICS // TAP_TO_DECRYPT",
                color = if (isRevealed) NeonPink else Color.Gray,
                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        AnimatedVisibility(visible = isRevealed) {
            Column(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF0A0505)).padding(16.dp)
            ) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("< PREV", color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { weekOffset++ })
                    val sdf = SimpleDateFormat("MMM dd", Locale.US)
                    Text("${sdf.format(Date(viewStartTime))} - ${sdf.format(Date(viewEndTime))}", color = Color.Gray, fontSize = 10.sp)
                    Text("NEXT >", color = if(weekOffset > 0) NeonCyan else Color.DarkGray, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { if(weekOffset > 0) weekOffset-- })
                }

                Spacer(Modifier.height(10.dp))

                if (currentViewLogs.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        Text("NO DATA IN SECTOR", color = Color.DarkGray, fontSize = 10.sp)
                    }
                } else {
                    PainGraph(currentViewLogs)
                }

                Spacer(Modifier.height(15.dp))

                if (currentViewLogs.isNotEmpty()) {
                    Text("EVENT LOG:", color = NeonPink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))

                    currentViewLogs.reversed().take(5).forEach { log ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date(log.timestamp)), color = Color.Gray, fontSize = 10.sp, modifier = Modifier.width(70.dp))
                            Box(Modifier.size(6.dp).background(NeonPink.copy(alpha = log.value/10f)))
                            Spacer(Modifier.width(8.dp))
                            Text("LVL ${log.value}", color = NeonPink, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
                            Text(log.note ?: "No Data", color = Color.White, fontSize = 10.sp, maxLines = 1)
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.DarkGray.copy(alpha=0.3f)))
                    }
                }

                Spacer(Modifier.height(15.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.DarkGray))
                Spacer(Modifier.height(10.dp))
                Text("EXPORT CONFIGURATION", color = NeonCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    NeonToggle(
                        checked = isClinicalExport,
                        onCheckedChange = { isClinicalExport = it },
                        activeColor = NeonCyan
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if(isClinicalExport) "MODE: CLINICAL" else "MODE: SYSTEM",
                        color = if(isClinicalExport) Color.White else NeonCyan,
                        fontSize = 10.sp, fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(8.dp))

                val rangeSdf = SimpleDateFormat("MM/dd", Locale.US)
                Row(
                    Modifier.fillMaxWidth().clickable { showDatePicker = true }.border(1.dp, Color.Gray, VitalityShape).padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("RANGE:", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("${rangeSdf.format(Date(activity.rangeStart))} -> ${rangeSdf.format(Date(activity.rangeEnd))}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("CHANGE", color = NeonCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(10.dp))

                val context = LocalContext.current
                CyberButtonBlock("GENERATE PDF // SHARE") {
                    val exportLogs = allLogs.filter {
                        it.type == "PAIN" && it.timestamp >= activity.rangeStart && it.timestamp <= activity.rangeEnd
                    }.sortedBy { it.timestamp }

                    val exportAudits = allAudits.filter {
                        it.timestampIssued >= activity.rangeStart && it.timestampIssued <= activity.rangeEnd
                    }

                    val pdfUri = PdfGenerator.generateReport(context, exportLogs, exportAudits, isClinicalExport)

                    if (pdfUri != null) {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_STREAM, pdfUri)
                            type = "application/pdf"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share Vitality Report"))
                    }
                }
            }
        }
    }
}

@Composable
fun DataGovernanceModule(activity: MainActivity) {
    var isRevealed by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        DateRangePickerModal(
            onDateSelected = { start, end ->
                activity.rangeStart = start ?: activity.rangeStart
                activity.rangeEnd = end ?: activity.rangeEnd
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false }
        )
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 40.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth().height(40.dp).clip(CutCornerShape(bottomEnd = 12.dp))
                .background(if (isRevealed) NeonPink.copy(alpha = 0.2f) else Color.DarkGray.copy(alpha = 0.3f))
                .clickable { isRevealed = !isRevealed },
            contentAlignment = Alignment.CenterStart
        ) {
            Text(if (isRevealed) "ADMIN // DATA_GOVERNANCE" else "ADMIN // SYSTEM_TOOLS", color = if (isRevealed) NeonPink else Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.padding(start = 16.dp))
        }

        AnimatedVisibility(visible = isRevealed) {
            Column(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF050A0A)).border(1.dp, NeonPink.copy(alpha=0.3f)).padding(16.dp)
            ) {
                Text("QUICK PURGE", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                    SmallActionButton("LAST 1H", Color.LightGray, Modifier.weight(1f)) { activity.deleteLastHour() }
                    SmallActionButton("LAST 24H", Color.LightGray, Modifier.weight(1f)) { activity.deleteLast24Hours() }
                }

                Spacer(Modifier.height(20.dp))
                Text("DEBUG TOOLS", color = NeonCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                CyberButtonBlock("INJECT 30-DAY FUZZY DATA (SEED: 1337)") {
                    activity.injectFuzzyData()
                }
                Spacer(Modifier.height(10.dp))
                CyberButtonBlock("INJECT OVERCHARGE (+25)") {
                    if (activity.currentHP == 100f) {
                        if (activity.overchargeStartTime == 0L) activity.overchargeStartTime = System.currentTimeMillis()
                        activity.overchargeStartTime -= (30 * 60 * 1000L)
                        activity.calculateHealth(Calendar.getInstance())

                        Wearable.getNodeClient(activity).connectedNodes.addOnSuccessListener { nodes ->
                            nodes.forEach { node ->
                                Wearable.getMessageClient(activity).sendMessage(node.id, "/sys/debug_overcharge", ByteArray(0))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text("SURGICAL DELETION", color = NeonPink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))

                val rangeSdf = SimpleDateFormat("MM/dd/yyyy", Locale.US)
                Row(
                    Modifier.fillMaxWidth().clickable { showDatePicker = true }.border(1.dp, NeonPink, VitalityShape).padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("TARGET:", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("${rangeSdf.format(Date(activity.rangeStart))} - ${rangeSdf.format(Date(activity.rangeEnd))}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(10.dp))
                CyberButtonBlock("DELETE SELECTED RANGE") { activity.deleteCustomRange() }

                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.DarkGray))
                Spacer(Modifier.height(20.dp))

                CyberButtonBlock("FACTORY RESET (LOGS ONLY)", color = Color.Red) { activity.wipeAllData() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangePickerModal(
    onDateSelected: (Long?, Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val datePickerState = rememberDateRangePickerState()

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onDateSelected(datePickerState.selectedStartDateMillis, datePickerState.selectedEndDateMillis)
                    onDismiss()
                }
            ) { Text("OK", color = NeonCyan) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) }
        },
        shape = VitalityShape,
        colors = DatePickerDefaults.colors(containerColor = Graphite)
    ) {
        DateRangePicker(
            state = datePickerState,
            title = { Text("Select Range", color = Color.White, modifier = Modifier.padding(16.dp)) },
            headline = { Text("Start - End", color = NeonCyan, modifier = Modifier.padding(16.dp)) },
            showModeToggle = false,
            colors = DatePickerDefaults.colors(
                containerColor = Graphite, titleContentColor = Color.White, headlineContentColor = NeonCyan,
                weekdayContentColor = NeonCyan, subheadContentColor = Color.Gray, yearContentColor = Color.White,
                currentYearContentColor = NeonCyan, selectedYearContentColor = Color.White,
                selectedDayContainerColor = NeonCyan, dayContentColor = Color.White, selectedDayContentColor = Color.Black,
                todayContentColor = NeonCyan, todayDateBorderColor = NeonCyan
            )
        )
    }
}

@Composable
fun PainGraph(logs: List<SystemLog>) {
    Canvas(modifier = Modifier.fillMaxWidth().height(150.dp)) {
        val w = size.width
        val h = size.height

        val stepY = h / 10f
        for (i in 0..10) {
            val y = h - (i * stepY)
            drawLine(Color.DarkGray.copy(alpha = 0.3f), Offset(0f, y), Offset(w, y), 1f)
        }

        if (logs.isEmpty()) return@Canvas

        val path = Path()
        val dotRadius = 4.dp.toPx()

        val startTime = logs.first().timestamp
        val endTime = logs.last().timestamp
        val timeSpan = (endTime - startTime).coerceAtLeast(1)

        logs.forEachIndexed { index, log ->
            val x = ((log.timestamp - startTime).toFloat() / timeSpan.toFloat()) * w
            val y = h - ((log.value.toFloat() / 10f) * h)

            if (index == 0) path.moveTo(x, y)
            else path.lineTo(x, y)

            drawCircle(NeonPink, radius = dotRadius, center = Offset(x, y))
        }
        drawPath(path, NeonPink, style = Stroke(width = 3f))
    }
}
