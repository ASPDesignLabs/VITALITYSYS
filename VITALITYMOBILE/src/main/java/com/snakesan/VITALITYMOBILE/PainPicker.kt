package com.snakesan.vitalitysys

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The phone's own pain-log screen: choose a 0-10 level and, optionally,
// describe it, all on one screen. Nothing is saved until SAVE (see
// MainActivity.savePainFromPhone), so Back / CANCEL ask before discarding
// anything chosen or typed. It reuses the locked-screen banner so it reads
// the same as the interrupt screens.
@Composable
fun PainPickerLayer(activity: MainActivity) {
    val level = activity.painPickerLevel
    val photoUri = activity.painPickerPhotoUri
    val haptic = LocalHapticFeedback.current

    Column(
        Modifier
            .fillMaxSize()
            .background(NeonBg.copy(alpha = 0.98f))
            .blockTouches()
            .systemBarsPadding()
            .imePadding()
    ) {
        LockStatusStrip(
            title = "PAIN LOG",
            phase = if (level == null) "NOW: CHOOSE A LEVEL" else "NOW: SAVE WHEN READY",
            rule = "NOTHING IS SAVED UNTIL YOU SAVE — BACK ASKS FIRST",
            flash = false,
            color = NeonPink,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("HOW IS IT RIGHT NOW?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text("( 0 = resolved, 10 = critical )", color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(20.dp))

            val pick: (Int) -> Unit = { n ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                activity.painPickerLevel = n
            }
            PainLevelRow(0..5, level, pick)
            Spacer(Modifier.height(6.dp))
            PainLevelRow(6..10, level, pick)

            Spacer(Modifier.height(16.dp))
            Text(
                level?.toString() ?: "–",
                color = if (level == null) Color.DarkGray else NeonPink,
                fontSize = 48.sp, fontWeight = FontWeight.Black
            )
            Text(
                level?.let { painDescriptor(it) } ?: "CHOOSE A LEVEL",
                color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                // announced by screen readers when the choice changes
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )

            Spacer(Modifier.height(24.dp))
            ConfigLabel("DETAILS (OPTIONAL)")
            Text("( Location / Type / Triggers )", color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            BasicTextField(
                value = activity.painPickerNote,
                onValueChange = { activity.painPickerNote = it },
                textStyle = TextStyle(color = NeonCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(NeonCyan),
                modifier = Modifier.fillMaxWidth().border(1.dp, NeonCyan, VitalityShape).padding(16.dp)
            )

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallActionButton(if (photoUri == null) "ATTACH PHOTO" else "RETAKE PHOTO", NeonCyan) {
                    activity.capturePainPhoto()
                }
                if (photoUri != null) {
                    SmallActionButton("REMOVE PHOTO", NeonPink) { activity.painPickerPhotoUri = null }
                }
            }
            photoUri?.let {
                Spacer(Modifier.height(12.dp))
                PhotoThumbnail(it, modifier = Modifier.size(120.dp))
            }

            Spacer(Modifier.height(28.dp))
            if (level != null) {
                CyberButtonBlock("SAVE PAIN LOG", NeonPink) { activity.savePainFromPhone() }
            } else {
                Text("CHOOSE A LEVEL TO SAVE", color = Color.DarkGray, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(12.dp))
            SmallActionButton("CANCEL", Color.Gray, Modifier.fillMaxWidth()) { activity.requestClosePainPicker() }
        }
    }

    if (activity.painPickerConfirmDiscard) {
        AlertDialog(
            onDismissRequest = { activity.painPickerConfirmDiscard = false },
            containerColor = Graphite,
            title = { Text("DISCARD THIS PAIN ENTRY?", color = NeonPink, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Nothing has been saved yet. Discarding throws away what you chose or typed.",
                    color = Color.White, fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { activity.closePainPicker() }) {
                    Text("DISCARD", color = NeonPink, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { activity.painPickerConfirmDiscard = false }) {
                    Text("KEEP EDITING", color = NeonCyan)
                }
            }
        )
    }
}

// One row of level buttons. Large (56 dp tall, evenly sharing the width) so a
// level is one easy tap, with no dragging. A short row is padded so every
// button ends up the same width.
@Composable
private fun PainLevelRow(levels: IntRange, selected: Int?, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        levels.forEach { n ->
            val isSelected = selected == n
            Box(
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(VitalityShape)
                    .background(if (isSelected) NeonPink.copy(alpha = 0.35f) else NeonPink.copy(alpha = 0.06f))
                    .border(if (isSelected) 2.dp else 1.dp, if (isSelected) NeonPink else Color.DarkGray, VitalityShape)
                    .semantics { contentDescription = "Pain level $n, ${painDescriptor(n).lowercase()}" }
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onPick(n) }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$n",
                    color = if (isSelected) Color.White else NeonPink,
                    fontSize = 20.sp, fontWeight = FontWeight.Black
                )
            }
        }
        if (levels.count() < 6) Spacer(Modifier.weight(1f))
    }
}
