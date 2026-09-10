package com.snakesan.vitalitysys

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text

// --- COLORS REMOVED (Now imported from Theme.kt) ---

@Composable
fun SyncOverlay(state: SyncState) {
    if (state == SyncState.HIDDEN) return

    val progressAnim = remember { Animatable(0f) }
    
    // Group states for logic
    val isConnecting = (state == SyncState.CONNECTING_RX || state == SyncState.CONNECTING_TX)
    val isAnimating = (state == SyncState.RECEIVING || state == SyncState.SENDING)

    LaunchedEffect(state) {
        if (isConnecting) {
            progressAnim.snapTo(0f)
        }
        if (isAnimating) {
            progressAnim.snapTo(0f) 
            progressAnim.animateTo(
                targetValue = 1f,
                animationSpec = keyframes {
                    durationMillis = 3000
                    0.0f at 0
                    0.15f at 300  
                    0.15f at 800  
                    0.60f at 1600 
                    0.65f at 2200 
                    1.0f at 3000  
                }
            )
        }
    }

    val borderColor = if (state == SyncState.SUCCESS) Color.Transparent else NeonPink
    val containerColor = if (state == SyncState.SUCCESS) NeonGreen else NeonPink.copy(alpha = 0.1f)
    val textColor = if (state == SyncState.SUCCESS) Color.Black else Color.White

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
            .padding(14.dp), 
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp) 
                .clip(CutCornerShape(topStart = 15.dp, bottomEnd = 15.dp))
                .background(containerColor)
                .border(2.dp, borderColor, CutCornerShape(topStart = 15.dp, bottomEnd = 15.dp))
        ) {
            // Progress Bar
            if (isAnimating || state == SyncState.SUCCESS) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(if(state == SyncState.SUCCESS) 1f else progressAnim.value)
                        .background(NeonAmber.copy(alpha = 0.7f))
                )
            }

            // Text Logic
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = when {
                        state == SyncState.SUCCESS -> "SYNC\nCOMPLETE"
                        isConnecting -> "[ CONNECTING ]"
                        state == SyncState.RECEIVING -> "[ RECEIVING ]\n${(progressAnim.value * 100).toInt()}%"
                        state == SyncState.SENDING -> "[ SENDING ]\n${(progressAnim.value * 100).toInt()}%"
                        else -> "..."
                    },
                    color = textColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 14.sp
                )
            }
        }
        
        // Deco Lines
        if (isAnimating || isConnecting) {
            Box(Modifier.align(Alignment.TopCenter).width(20.dp).height(2.dp).background(NeonPink))
            Box(Modifier.align(Alignment.BottomCenter).width(20.dp).height(2.dp).background(NeonPink))
        }
    }
}
