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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MIN_TOUCH_TARGET = 44.dp

@Composable
fun CyberButtonBlock(text: String, color: Color = NeonCyan, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(VitalityHeroShape)
            .background(color.copy(alpha = 0.12f)).border(2.dp, color, VitalityHeroShape)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text.uppercase(),
            color = color,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

@Composable
fun ProtocolCard(protocol: Protocol, current: Int, target: Int, unit: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val color = Color(protocol.colorHex)
    val progress = (current.toFloat() / target.toFloat()).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "CardProgress")

    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(VitalityShape)
            .background(color.copy(alpha = 0.05f)).border(1.dp, if(expanded) color else Color.DarkGray, VitalityShape)
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

// A small inline action chip, used for "+ ADD" / "REMOVE" controls in the
// variable-length schedule editors (meals, medications, hygiene tasks).
@Composable
fun SmallActionButton(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .heightIn(min = MIN_TOUCH_TARGET)
            .clip(VitalityShape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color, VitalityShape)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text.uppercase(), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}

// Cut-corner cyberpunk stand-in for the stock Material pill-shaped Switch, so
// on/off toggles match the rest of the NEON design language instead of
// standing out as a default-themed control. Ported from ACK's own NeonToggle.
@Composable
fun NeonToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = NeonCyan
) {
    val haptic = LocalHapticFeedback.current
    val thumbOffset = animateDpAsState(
        targetValue = if (checked) 26.dp else 0.dp,
        animationSpec = tween(150),
        label = "toggleThumb"
    ).value

    Box(
        modifier = modifier
            .width(56.dp)
            .height(28.dp)
            .border(1.dp, if (checked) activeColor else Color.DarkGray, CutCornerShape(6.dp))
            .background(if (checked) activeColor.copy(alpha = 0.12f) else Color.Transparent, CutCornerShape(6.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(!checked)
            }
            .padding(3.dp)
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(20.dp)
                .background(if (checked) activeColor else Color.Gray, CutCornerShape(3.dp))
        )
    }
}