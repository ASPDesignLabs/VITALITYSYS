package com.snakesan.vitalitysys

// ADDED THIS IMPORT:
import androidx.compose.animation.AnimatedVisibility

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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

// ---------------------------------------------------------------------------
// LOCK CHROME (shared by the interrupt and nutrition overlays)
// ---------------------------------------------------------------------------

// Swallows every touch that lands on it, so nothing underneath an overlay can
// be tapped or scrolled while it's up. A sibling drawn on top only blocks
// what's below it if something on it actually handles pointer input — a plain
// background (or `clickable(enabled = false)`) lets touches fall through.
fun Modifier.blockTouches(): Modifier = pointerInput(Unit) { detectTapGestures { } }

const val HOLD_TO_SKIP_MILLIS = 3000

// One-line "what's locked, where am I, how do I leave" banner pinned to the
// top of every locked screen. The second line is also where a blocked Back
// press explains itself (flash = true), so a dead button is never silent.
@Composable
fun LockStatusStrip(
    title: String,
    phase: String,
    rule: String,
    flash: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(VitalityShape)
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.5f), VitalityShape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            // Polite live region: TalkBack announces the phase/rule when it
            // changes, so a screen-reader user isn't left guessing either.
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(title, color = color, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            Text(phase, color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            rule,
            color = if (flash) NeonPink else Color.LightGray,
            fontSize = 11.sp,
            fontWeight = if (flash) FontWeight.Black else FontWeight.Bold
        )
    }
}

// The deliberate way out under FIRM: press and hold for HOLD_TO_SKIP_MILLIS
// with a visible fill + countdown; letting go early resets it. Back stays
// off, but this guarantees there is always a way out that can't happen by
// accident.
@Composable
fun HoldToSkipButton(modifier: Modifier = Modifier, onSkip: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var holding by remember { mutableStateOf(false) }
    val currentOnSkip by rememberUpdatedState(onSkip)
    val totalSeconds = HOLD_TO_SKIP_MILLIS / 1000
    val secondsLeft = kotlin.math.ceil((1f - progress.value) * totalSeconds).toInt().coerceIn(1, totalSeconds)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(VitalityShape)
            .background(NeonPink.copy(alpha = 0.06f))
            .border(1.dp, NeonPink, VitalityShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    holding = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val fill = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(HOLD_TO_SKIP_MILLIS, easing = LinearEasing))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnSkip()
                    }
                    waitForUpOrCancellation()
                    holding = false
                    if (fill.isActive) {
                        fill.cancel()
                        scope.launch { progress.animateTo(0f, tween(150)) }
                    }
                }
            }
            // A timed press-and-hold can't be done with every assistive
            // setup (switch access, some TalkBack gestures), so screen readers
            // get the same exit as a plain action instead of being trapped.
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Hold for $totalSeconds seconds to skip this interrupt"
                customActions = listOf(CustomAccessibilityAction("Skip this interrupt") { currentOnSkip(); true })
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(progress.value)
                .background(NeonPink.copy(alpha = 0.35f))
        )
        Text(
            if (holding) "KEEP HOLDING… $secondsLeft" else "HOLD ${totalSeconds}s TO SKIP",
            color = NeonPink,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
    }
}
