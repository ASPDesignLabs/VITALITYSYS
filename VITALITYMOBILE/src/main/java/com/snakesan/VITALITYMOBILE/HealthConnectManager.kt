package com.snakesan.vitalitysys

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Volume
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectManager(private val context: Context) {
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    val requiredPermissions = setOf(
        HealthPermission.getWritePermission(HydrationRecord::class),
        HealthPermission.getWritePermission(NutritionRecord::class)
    )

    suspend fun hasAllPermissions(): Boolean {
        return try {
            val granted = client.permissionController.getGrantedPermissions()
            granted.containsAll(requiredPermissions)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun logWater(milliliters: Double) {
        if (!hasAllPermissions()) return
        
        try {
            val endTime = Instant.now()
            val startTime = endTime.minusSeconds(10)

            val record = HydrationRecord(
                volume = Volume.milliliters(milliliters),
                startTime = startTime,
                endTime = endTime,
                startZoneOffset = ZoneOffset.systemDefault().rules.getOffset(startTime),
                endZoneOffset = ZoneOffset.systemDefault().rules.getOffset(endTime)
            )
            client.insertRecords(listOf(record))
            Log.d("HEALTH_CONNECT", "Hydration written: $milliliters ml")
        } catch (e: Exception) {
            Log.e("HEALTH_CONNECT", "Failed to write hydration", e)
        }
    }

    suspend fun logMeal(
        cal: Double, 
        fat: Double, 
        protein: Double, 
        fiber: Double, 
        sugar: Double
    ) {
        if (!hasAllPermissions()) return
        
        try {
            val endTime = Instant.now()
            val startTime = endTime.minusSeconds(60)

            val record = NutritionRecord(
                name = "VITALITY_MEAL_VECTOR",
                energy = Energy.kilocalories(cal),
                totalFat = Mass.grams(fat),
                protein = Mass.grams(protein),
                dietaryFiber = Mass.grams(fiber),
                sugar = Mass.grams(sugar),
                startTime = startTime,
                endTime = endTime,
                startZoneOffset = ZoneOffset.systemDefault().rules.getOffset(startTime),
                endZoneOffset = ZoneOffset.systemDefault().rules.getOffset(endTime)
            )
            client.insertRecords(listOf(record))
            Log.d("HEALTH_CONNECT", "Meal written: $cal kcal")
        } catch (e: Exception) {
            Log.e("HEALTH_CONNECT", "Failed to write nutrition", e)
        }
    }
}
