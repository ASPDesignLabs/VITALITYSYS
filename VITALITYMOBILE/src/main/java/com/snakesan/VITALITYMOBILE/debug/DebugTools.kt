package com.snakesan.vitalitysys.debug

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snakesan.vitalitysys.BuildConfig
import com.snakesan.vitalitysys.CyberButtonBlock
import com.snakesan.vitalitysys.DateRangePickerModal
import com.snakesan.vitalitysys.MainActivity
import com.snakesan.vitalitysys.NeonCyan
import com.snakesan.vitalitysys.NeonPink
import com.snakesan.vitalitysys.SmallActionButton
import com.snakesan.vitalitysys.VitalityShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The ADMIN panel: fuzzy-data injection, overcharge injection, quick purge,
// surgical deletion, and factory reset. Gated two ways: it doesn't exist at
// all unless this is a debug build (BuildConfig.DEBUG — see build.gradle.kts),
// and even then it stays collapsed/off until you long-press the header to
// switch DebugFlags on for testing, then switch it back off.
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DebugPanel(activity: MainActivity) {
    if (!BuildConfig.DEBUG) return

    val context = LocalContext.current
    var debugEnabled by remember { mutableStateOf(DebugFlags.isEnabled(context)) }
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

    fun toggleDebugFlag() {
        debugEnabled = DebugFlags.toggle(context)
        if (!debugEnabled) isRevealed = false
        Toast.makeText(
            context,
            if (debugEnabled) "DEBUG TOOLS ENABLED" else "DEBUG TOOLS DISABLED",
            Toast.LENGTH_SHORT
        ).show()
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 40.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(CutCornerShape(bottomEnd = 12.dp))
                .background(if (isRevealed) NeonPink.copy(alpha = 0.2f) else Color.DarkGray.copy(alpha = 0.3f))
                .combinedClickable(
                    onClick = { if (debugEnabled) isRevealed = !isRevealed },
                    onLongClick = { toggleDebugFlag() }
                ),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = when {
                    !debugEnabled -> "ADMIN // DISABLED (LONG-PRESS TO ENABLE)"
                    isRevealed -> "ADMIN // DATA_GOVERNANCE"
                    else -> "ADMIN // SYSTEM_TOOLS"
                },
                color = if (isRevealed) NeonPink else Color.Gray,
                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        AnimatedVisibility(visible = debugEnabled && isRevealed) {
            Column(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF050A0A)).border(1.dp, NeonPink.copy(alpha = 0.3f)).padding(16.dp)
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
                    activity.injectDebugOvercharge()
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
