package com.snakesan.vitalitysys

// ADDED THIS IMPORT:
import androidx.compose.animation.AnimatedVisibility

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
fun ProtocolCard(protocol: Protocol, current: Int, target: Int, unit: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val color = Color(protocol.colorHex)
    val progress = (current.toFloat() / target.toFloat()).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "CardProgress")

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