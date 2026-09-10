package com.snakesan.vitalitysys

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
fun NutritionOverlay(onLogMeal: (cal: Float, fat: Float, pro: Float, fiber: Float, sugar: Float) -> Unit) {
    var calories by remember { mutableStateOf(500f) }
    var protein by remember { mutableStateOf(30f) }
    var fat by remember { mutableStateOf(15f) }
    var fiber by remember { mutableStateOf(5f) }
    var sugar by remember { mutableStateOf(10f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NeonBg.copy(alpha = 0.98f))
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "MACRONUTRIENT INGESTION SCAN",
                color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp
            )
            Spacer(Modifier.height(30.dp))

            // Calories: 0 to 2000, 19 steps (means ticks exactly every 100)
            MacroSlider("ENERGY KINETICS (CALORIES)", calories, 0f..2000f, 19, NeonAmber, "kcal") { calories = it }
            
            // Protein: 0 to 150g, 29 steps (ticks every 5g)
            MacroSlider("PROTEIN (TISSUE REPAIR)", protein, 0f..150f, 29, NeonCyan, "g") { protein = it }
            
            // Fat: 0 to 100g, 19 steps (ticks every 5g)
            MacroSlider("LIPIDS / FAT", fat, 0f..100f, 19, NeonPink, "g") { fat = it }
            
            // Fiber: 0 to 50g, 9 steps (ticks every 5g)
            MacroSlider("FIBER (GUT FLORA)", fiber, 0f..50f, 9, NeonGreen, "g") { fiber = it }
            
            // Sugar: 0 to 100g, 19 steps (ticks every 5g)
            MacroSlider("SUGARS (RAPID GLUCOSE)", sugar, 0f..100f, 19, Color.White, "g") { sugar = it }

            Spacer(Modifier.height(40.dp))

            CyberButtonBlock("UPLOAD NUTRITION TO MAINFRAME") {
                onLogMeal(calories, fat, protein, fiber, sugar)
            }
        }
    }
}

@Composable
fun MacroSlider(
    label: String, 
    value: Float, 
    range: ClosedFloatingPointRange<Float>, 
    steps: Int, 
    color: Color, 
    unit: String, 
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(label, color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("${value.toInt()}$unit", color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = color, 
                activeTrackColor = color,
                inactiveTrackColor = Color.DarkGray
            )
        )
    }
}
