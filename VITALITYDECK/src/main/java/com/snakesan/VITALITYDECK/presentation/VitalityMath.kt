package com.snakesan.vitalitysys

import java.util.Calendar

object VitalityMath {

    data class VitalityPayload(
        val hp: Int,
        val hydStatus: Int,
        val mealStatus: Int
    )

    /**
     * Calculates the "Damage Over Time" for a single protocol based on timestamps.
     * @param currentMins Minutes since midnight
     * @param targetMins Expected completion time in minutes
     * @param grace Grace period in minutes before damage begins
     * @param baseDmg Initial damage applied once grace period expires
     * @param tickRate How many minutes it takes to bleed 1 additional HP
     */
    private fun calculateDoT(
        currentMins: Int,
        targetMins: Int,
        grace: Int,
        baseDmg: Int,
        tickRate: Int
    ): Int {
        val minutesLate = currentMins - targetMins - grace
        if (minutesLate <= 0) return 0 // No penalty if within grace period
        
        // Base penalty + continuous bleed based on how late we are
        return baseDmg + (minutesLate / tickRate)
    }

    /**
     * Evaluates the full system state and applies the Bio-Drift combo multiplier.
     */
    fun calculateSystemStatus(
        nutrientCount: Int,
        hydrationCount: Int,
        medsTaken: Boolean,
        maintDone: Boolean,
        config: SysConfig
    ): VitalityPayload {
        val now = Calendar.getInstance()
        val currentMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        
        var totalDamage = 0
        var failingProtocols = 0

        // 1. NUTRIENT CHECK (Meals)
        var mealDamage = 0
        if (nutrientCount < 1) {
            mealDamage += calculateDoT(currentMins, config.meal1Time.toInt(), 30, 5, 5)
        }
        if (nutrientCount < 2) {
            mealDamage += calculateDoT(currentMins, config.meal2Time.toInt(), 30, 5, 5)
        }
        if (nutrientCount < 3) {
            mealDamage += calculateDoT(currentMins, config.meal3Time.toInt(), 30, 5, 5)
        }
        
        if (mealDamage > 0) {
            totalDamage += mealDamage
            failingProtocols++
        }

        // 2. CHEMISTRY CHECK (Meds)
        if (!medsTaken) {
            val dayOfWeek = now.get(Calendar.DAY_OF_WEEK)
            val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
            val target = if (isWeekend) config.medsWeekend.toInt() else config.medsWeekday.toInt()
            
            // Meds are critical: 10 base damage, ticks every 3 minutes
            val medsDamage = calculateDoT(currentMins, target, 30, 10, 3)
            if (medsDamage > 0) {
                totalDamage += medsDamage
                failingProtocols++
            }
        }

        // 3. HYDRATION CHECK (Volume over time)
        var hydroDamage = 0
        val startMins = config.activeStartHour.toInt() * 60
        val endMins = config.activeEndHour.toInt() * 60
        
        if (currentMins in startMins..endMins) {
            val totalActive = endMins - startMins
            val elapsedActive = currentMins - startMins
            
            // Integer approximation of expected progress
            val expectedMl = (config.hydrationTargetMl.toInt() * elapsedActive) / totalActive
            val actualMl = hydrationCount * 250
            val deficitMl = expectedMl - actualMl
            
            // Grace amount: 500ml (2 drinks) behind is fine.
            if (deficitMl > 500) {
                // 5 Base damage + 1 damage per 100ml behind target
                hydroDamage = 5 + ((deficitMl - 500) / 100)
                totalDamage += hydroDamage
                failingProtocols++
            }
        }

        // 4. MAINTENANCE CHECK (Hygiene/Reset)
        if (!maintDone) {
            val maintDamage = calculateDoT(currentMins, config.maint1Time.toInt(), 60, 5, 10)
            if (maintDamage > 0) {
                totalDamage += maintDamage
                failingProtocols++
            }
        }

        // --- BIO-DRIFT COMBO SYNERGY ---
        // If multiple protocols are failing, systems degrade exponentially.
        if (failingProtocols > 1) {
            val driftPenalty = (totalDamage * (failingProtocols - 1)) / 2
            totalDamage += driftPenalty
        }

        // Calculate final bounded HP
        val finalHp = (100 - totalDamage).coerceIn(0, 100)

        // --- CALCULATE SUB-STATUSES FOR OVERSEER HUD ---
        val hydStatus = when {
            hydroDamage > 0 -> 0 // 0 = DRY / DRIFTING
            currentMins in startMins..endMins && (hydrationCount * 250) > ((config.hydrationTargetMl.toInt() * (currentMins - startMins)) / (endMins - startMins)) + 500 -> 2 // 2 = SATURATED
            else -> 1 // 1 = NORMAL
        }

        val mealStatus = when {
            mealDamage > 0 -> 2 // 2 = REQUIRED (Bleeding)
            nutrientCount < 3 && currentMins > (listOf(config.meal1Time, config.meal2Time, config.meal3Time)[nutrientCount].toInt() - 30) -> 1 // 1 = PREP (Within 30 mins)
            else -> 0 // 0 = CLEAR
        }

        return VitalityPayload(finalHp, hydStatus, mealStatus)
    }
}
