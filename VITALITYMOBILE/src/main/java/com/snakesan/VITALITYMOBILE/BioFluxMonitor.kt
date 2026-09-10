package com.snakesan.vitalitysys

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import java.lang.Math.sin

@Composable
fun BioFluxMonitor(
    healthPercentage: Float, // 0.0f to 100.0f
    overcharge: Int,         // Tracking max of 50
    modifier: Modifier = Modifier
) {
    val isMaxOvercharge = overcharge >= 50

    // DETERMINE STATE BASED ON HP & OVERCHARGE
    val stateColor = when {
        isMaxOvercharge -> NeonBg // Inverted: EKG line becomes the background color (Black)
        healthPercentage >= 80f -> NeonGreen
        healthPercentage >= 60f -> NeonAmber
        else -> NeonPink
    }

    val bgColor = if (isMaxOvercharge) NeonPink else NeonBg
    val activeGridColor = if (isMaxOvercharge) NeonBg.copy(alpha = 0.2f) else Color(0xFF1A0A0F)

    val statusText = when {
        isMaxOvercharge -> "MAX OVERCHARGE // EDGERUNNER" // Shortened slightly
        healthPercentage >= 80f -> "SYSTEM OPTIMAL // VITALS STABLE"
        healthPercentage >= 60f -> "WARNING: HOMEOSTASIS DEGRADING"
        healthPercentage >= 40f -> "CRITICAL ERROR: INTEGRITY FAILING"
        else -> "EMERGENCY: SYSTEM SHUTDOWN IMMINENT"
    }

    // CALCULATE BPM & SCROLL SPEED
    val panicFactor = ((100f - healthPercentage) / 100f).coerceIn(0f, 1f)
    val targetBpm = 60 + (35 * panicFactor) 
    val beatIntervalMs = (60000 / targetBpm).toLong()
    val scrollSpeed = 0.3f + (0.3f * panicFactor) 

    val timeSource = remember { mutableLongStateOf(System.currentTimeMillis()) }
    
    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameMillis { time -> timeSource.longValue = System.currentTimeMillis() }
        }
    }

    Column(modifier = modifier.fillMaxWidth().fillMaxHeight()) {
        // --- HEADER ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "CURRENT_STATE",
                color = NeonCyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                maxLines = 1
            )
            Text(
                text = statusText,
                // CHANGE THIS LINE: If maxed, use NeonPink so it shows up on the dark background!
                color = if (isMaxOvercharge) NeonPink else stateColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        // --- MONITOR SCREEN ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f) // Use weight instead of fillMaxHeight so the shield bar fits below
                .background(bgColor, shape = CutCornerShape(topEnd = 20.dp, bottomStart = 20.dp))
                .border(
                    width = 1.dp, 
                    color = stateColor.copy(alpha=0.5f), 
                    shape = CutCornerShape(topEnd = 20.dp, bottomStart = 20.dp)
                )
                .clip(CutCornerShape(topEnd = 20.dp, bottomStart = 20.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val midY = h / 2
                val now = timeSource.longValue

                // A. Draw Grid (Static)
                val gridStep = 40f
                for (x in 0..w.toInt() step gridStep.toInt()) {
                    drawLine(activeGridColor, start = Offset(x.toFloat(), 0f), end = Offset(x.toFloat(), h), strokeWidth = 1f)
                }
                for (y in 0..h.toInt() step gridStep.toInt()) {
                    drawLine(activeGridColor, start = Offset(0f, y.toFloat()), end = Offset(w, y.toFloat()), strokeWidth = 1f)
                }

                // B. Draw EKG Trace
                val path = Path()
                path.moveTo(0f, midY)

                for (x in 0 until w.toInt() step 3) {
                    val xPos = x.toFloat()
                    val timeAtPixel = now - ((w - xPos) / scrollSpeed).toLong()
                    val phase = (timeAtPixel % beatIntervalMs).toFloat() / beatIntervalMs

                    var yOffset = 0f
                    if (phase > 0.1 && phase < 0.15) yOffset = -10f 
                    else if (phase > 0.18 && phase < 0.2) yOffset = 10f 
                    else if (phase > 0.2 && phase < 0.26) yOffset = -70f 
                    else if (phase > 0.26 && phase < 0.3) yOffset = 25f 
                    else if (phase > 0.4 && phase < 0.5) yOffset = -15f 

                    val jitter = if (healthPercentage < 40f) ((Math.random() * 6 - 3).toFloat()) else 0f
                    val noise = sin((xPos + now / 20.0) / 10.0).toFloat() * 1.5f

                    val finalY = midY + yOffset + noise + jitter

                    if (x == 0) path.moveTo(xPos, finalY)
                    else path.lineTo(xPos, finalY)
                }

                drawPath(path = path, color = stateColor, style = Stroke(width = 3f))

                // C. CRT Fade Effects (Uses bgColor for inversion!)
                drawRect(brush = Brush.horizontalGradient(colors = listOf(bgColor, Color.Transparent), startX = 0f, endX = 60f))
                drawRect(brush = Brush.horizontalGradient(colors = listOf(Color.Transparent, bgColor), startX = size.width - 60f, endX = size.width))
            }

            // D. Blinking Indicator
            val beatPhase = (System.currentTimeMillis() % beatIntervalMs).toFloat() / beatIntervalMs
            if (beatPhase < 0.2f) { 
                Canvas(modifier = Modifier.padding(10.dp).align(Alignment.TopEnd).size(8.dp)) {
                    drawCircle(stateColor)
                }
            }
        }

        // --- SHIELD BAR (OVERCHARGE) ---
        // Placed safely outside the Canvas and Box, inside the Column
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "OC_SHIELD",
                color = NeonCyan,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(60.dp)
            )
            Box(
                modifier = Modifier.fillMaxWidth().height(6.dp)
                    .background(Color.DarkGray.copy(alpha = 0.5f))
                    .border(1.dp, NeonCyan)
            ) {
                Box(
                    modifier = Modifier.fillMaxHeight()
                        .fillMaxWidth(overcharge / 50f)
                        .background(if (isMaxOvercharge) NeonPink else NeonCyan)
                )
            }
        }
    }
}
