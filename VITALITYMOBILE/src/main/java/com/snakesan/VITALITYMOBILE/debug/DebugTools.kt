package com.snakesan.vitalitysys.debug

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snakesan.vitalitysys.BuildConfig
import com.snakesan.vitalitysys.CyberButtonBlock
import com.snakesan.vitalitysys.DateRangePickerModal
import com.snakesan.vitalitysys.MainActivity
import com.snakesan.vitalitysys.NeonPink
import com.snakesan.vitalitysys.SmallActionButton
import com.snakesan.vitalitysys.VitalityShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The ADMIN panel: quick purge, surgical deletion, and factory reset. Gated
// two ways: it doesn't exist at all unless this is a debug build
// (BuildConfig.DEBUG — see build.gradle.kts), and even then it's only ever
// composed into the tree when the caller (VitalityDashboard's header,
// three-tap-the-period gesture) decides to show it.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugPanel(activity: MainActivity) {
    if (!BuildConfig.DEBUG) return

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
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(CutCornerShape(bottomEnd = 12.dp))
                .background(NeonPink.copy(alpha = 0.2f)),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "ADMIN // DATA_GOVERNANCE",
                color = NeonPink,
                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

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
