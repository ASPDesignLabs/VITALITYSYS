package com.snakesan.vitalitysys

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

class PrivacyRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Tells Android we are handling our own system insets (dodging the notch/status bar)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            NeonTheme {
                // The outer box centers everything and applies systemBarsPadding to prevent overdraw
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(NeonBg)
                        .systemBarsPadding(), 
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                    ) {
                        // --- MODULE HEADER (Expanded Style) ---
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .clip(CutCornerShape(bottomEnd = 12.dp))
                                .background(NeonPink.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                "ADMIN // DATA_GOVERNANCE_PROTOCOL",
                                color = NeonPink,
                                fontSize = 10.sp, 
                                fontWeight = FontWeight.Bold, 
                                letterSpacing = 2.sp, 
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }

                        // --- MODULE BODY ---
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF050A0A))
                                .border(1.dp, NeonPink.copy(alpha = 0.3f))
                                .padding(20.dp)
                        ) {
                            Text(
                                "VITALITY.SYS operates on a strict self-sovereign data matrix.\n\n" +
                                "By engaging the Health Connect bridge, telemetry regarding hydration and macromolecular ingestion is syndicated strictly locally to your device's core repository.\n\n" +
                                "We do not broadcast your physiological status to megacorps. The system syncs to Health Connect solely to harmonize your dashboard parameters.",
                                color = Color.LightGray, 
                                fontSize = 12.sp, 
                                lineHeight = 18.sp
                            )
                            
                            Spacer(Modifier.height(40.dp))
                            
                            CyberButtonBlock("ACKNOWLEDGE // RETURN") {
                                finish()
                            }
                        }
                    }
                }
            }
        }
    }
}
