package com.snakesan.vitalitysys

import java.util.Calendar
import com.snakesan.vitalitysys.data.NotificationAudit

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
     * @param completedKeys the set of today's completed dose/hygiene-task keys
     *   (see SysConfig.doseKey / hygieneKey) — replaces the old single
     *   medsTaken/maintDone booleans now that each protocol can hold any
     *   number of user-defined reminders.
     */
    fun calculateSystemStatus(
        nutrientCount: Int,
        hydrationCount: Int,
        completedKeys: Set<String>,
        config: SysConfig
    ): VitalityPayload {
        val now = Calendar.getInstance()
        val currentMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        var totalDamage = 0
        var failingProtocols = 0

        // 1. NUTRIENT CHECK (Meals)
        var mealDamage = 0
        config.mealTimes.forEachIndexed { index, mealTime ->
            if (nutrientCount < index + 1) {
                mealDamage += calculateDoT(currentMins, mealTime, 30, 5, 5)
            }
        }

        if (mealDamage > 0) {
            totalDamage += mealDamage
            failingProtocols++
        }

        // 2. CHEMISTRY CHECK (Meds — any number of medications, each with any number of daily doses)
        var medsDamage = 0
        config.allDoses().forEach { dose ->
            if (dose.key !in completedKeys) {
                // Meds are critical: 10 base damage, ticks every 3 minutes
                medsDamage += calculateDoT(currentMins, dose.time, 30, 10, 3)
            }
        }
        if (medsDamage > 0) {
            totalDamage += medsDamage
            failingProtocols++
        }

        // 3. HYDRATION CHECK (Volume over time)
        var hydroDamage = 0
        val startMins = config.activeStartHour.toInt() * 60
        val endMins = config.activeEndHour.toInt() * 60
        val totalActive = endMins - startMins

        // totalActive <= 0 means the active window is zero-width or
        // inverted (e.g. start == end) — treat hydration tracking as off
        // for now instead of dividing by zero.
        if (totalActive > 0 && currentMins in startMins..endMins) {
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

        // 4. MAINTENANCE CHECK (any number of user-defined hygiene tasks)
        var maintDamage = 0
        config.hygieneTasks.forEach { task ->
            val key = SysConfig.hygieneKey(task.id)
            if (key !in completedKeys) {
                maintDamage += calculateDoT(currentMins, task.time, 60, 5, 10)
            }
        }
        if (maintDamage > 0) {
            totalDamage += maintDamage
            failingProtocols++
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
            totalActive > 0 && currentMins in startMins..endMins && (hydrationCount * 250) > ((config.hydrationTargetMl.toInt() * (currentMins - startMins)) / totalActive) + 500 -> 2 // 2 = SATURATED
            else -> 1 // 1 = NORMAL
        }

        val mealStatus = when {
            mealDamage > 0 -> 2 // 2 = REQUIRED (Bleeding)
            nutrientCount < config.mealTimes.size && currentMins > (config.mealTimes[nutrientCount] - 30) -> 1 // 1 = PREP (Within 30 mins)
            else -> 0 // 0 = CLEAR
        }

        return VitalityPayload(finalHp, hydStatus, mealStatus)
    }

    // --- AUDIT & COMPLIANCE LOGIC ---
    //
    // Grading is per TASK, not per alert. The watch re-alerts the same
    // overdue item every ~15 minutes and each alert is its own audit row, so
    // grading rows would charge one late dose several times over. Rows are
    // instead chained into tasks (see buildTasks) and each task gets one
    // outcome:
    //   ON_TIME - finished within ON_TIME_MINUTES of the first alert  (1.0)
    //   SLOW    - finished later, but the same day                    (0.5)
    //   MISSED  - not finished by the end of the day it was raised    (0.0)
    //   OPEN    - not finished yet and the day isn't over: not graded yet
    // Skipped or unanswered alerts are noted in the explanation but never
    // decide the outcome — finishing is what counts. How fast the first tap
    // came is reported, but doesn't change the grade.

    const val ON_TIME_MINUTES = 60L

    enum class TaskOutcome { ON_TIME, SLOW, MISSED, OPEN }

    data class GradedTask(
        val protocolType: String,
        val itemKey: String?, // null = an alert from before item keys existed
        val firstIssued: Long,
        val alertCount: Int,
        val outcome: TaskOutcome,
        // Whole minutes from the first alert to completion; null if never done.
        // (Note a MISSED task can still have one: finished only after midnight.)
        val finishedAfterMins: Long?,
        val reason: String // plain-language explanation shown in the app
    )

    data class AuditGrade(
        val gradedTasks: Int, // ON_TIME + SLOW + MISSED (OPEN isn't graded yet)
        val onTimeCount: Int,
        val slowCount: Int,
        val missedCount: Int,
        val openCount: Int,
        val compliancePercentage: Int, // 0 to 100
        val grade: String // A, B, C, D, F, or N/A when nothing is graded yet
    )

    /**
     * Chains alert rows into tasks and grades each one. A task is every alert
     * for the same protocol + item on the same day, up to and including the
     * one that got fulfilled; an alert after that starts a new task. Rows
     * from before item keys existed (itemKey == null) can't be chained, so
     * each stands alone as its own task.
     */
    fun buildTasks(audits: List<NotificationAudit>, now: Long = System.currentTimeMillis()): List<GradedTask> {
        val chains = mutableListOf<List<NotificationAudit>>()
        val open = LinkedHashMap<Triple<String, String, Long>, MutableList<NotificationAudit>>()

        audits.sortedBy { it.timestampIssued }.forEach { audit ->
            val itemKey = audit.itemKey
            if (itemKey == null) {
                chains += listOf(audit)
                return@forEach
            }
            val chainKey = Triple(audit.protocolType, itemKey, dayStart(audit.timestampIssued))
            val chain = open.getOrPut(chainKey) { mutableListOf() }
            chain += audit
            if (audit.timestampFulfilled != null) {
                chains += chain.toList()
                open.remove(chainKey)
            }
        }
        chains += open.values.map { it.toList() }

        return chains.map { gradeChain(it, now) }.sortedByDescending { it.firstIssued }
    }

    private fun gradeChain(chain: List<NotificationAudit>, now: Long): GradedTask {
        val first = chain.first()
        val firstIssued = first.timestampIssued
        val dayEnds = nextDayStart(firstIssued)
        val finishedAt = chain.mapNotNull { it.timestampFulfilled }.minOrNull()
        val finishedAfterMillis = finishedAt?.let { it - firstIssued }

        val outcome = when {
            finishedAt != null && finishedAt >= dayEnds -> TaskOutcome.MISSED
            finishedAfterMillis != null && finishedAfterMillis <= ON_TIME_MINUTES * 60_000L -> TaskOutcome.ON_TIME
            finishedAfterMillis != null -> TaskOutcome.SLOW
            now >= dayEnds -> TaskOutcome.MISSED
            else -> TaskOutcome.OPEN
        }

        // First real tap = the earliest interaction on a row that wasn't a
        // skip or a no-reply. (A row that was marked unanswered and then
        // finished anyway keeps that mark, so it doesn't count as a tap.)
        val tappedAt = chain
            .filter { it.interactionType != "DISMISSED" && it.interactionType != "IGNORED" }
            .mapNotNull { it.timestampInteracted }
            .minOrNull()

        val headline = when (outcome) {
            TaskOutcome.ON_TIME -> "Done ${formatMinutes(finishedAfterMillis!! / 60_000L)} after the first alert."
            TaskOutcome.SLOW ->
                "Done ${formatMinutes(finishedAfterMillis!! / 60_000L)} after the first alert — past the ${ON_TIME_MINUTES}m mark."
            TaskOutcome.MISSED ->
                if (finishedAt != null) "Finished only after the day had ended." else "Not done by the end of the day."
            TaskOutcome.OPEN -> "Not done yet — the day isn't over."
        }

        val notes = mutableListOf<String>()
        if (chain.size > 1) notes += "${chain.size} alerts"
        val skipped = chain.count { it.interactionType == "DISMISSED" }
        if (skipped > 0) notes += "skipped ${skipped}x"
        // The alert that ended up finished obviously got a response, even if
        // it had been auto-marked unanswered at the 5-minute mark first.
        val unanswered = chain.count { it.interactionType == "IGNORED" && it.timestampFulfilled == null }
        if (unanswered > 0) notes += "$unanswered unanswered"
        if (tappedAt != null && (finishedAt == null || tappedAt < finishedAt)) {
            notes += "first tap after ${formatMinutes((tappedAt - firstIssued) / 60_000L)}"
        }

        return GradedTask(
            protocolType = first.protocolType,
            itemKey = first.itemKey,
            firstIssued = firstIssued,
            alertCount = chain.size,
            outcome = outcome,
            finishedAfterMins = finishedAfterMillis?.div(60_000L),
            reason = if (notes.isEmpty()) headline else "${headline.trimEnd('.')} (${notes.joinToString(", ")})."
        )
    }

    /** Aggregate score over already-built tasks. OPEN tasks aren't graded yet. */
    fun gradeTasks(tasks: List<GradedTask>): AuditGrade {
        val onTime = tasks.count { it.outcome == TaskOutcome.ON_TIME }
        val slow = tasks.count { it.outcome == TaskOutcome.SLOW }
        val missed = tasks.count { it.outcome == TaskOutcome.MISSED }
        val open = tasks.count { it.outcome == TaskOutcome.OPEN }
        val graded = onTime + slow + missed

        if (graded == 0) return AuditGrade(0, 0, 0, 0, open, 100, "N/A")

        // On time = 1.0, Slow = 0.5, Missed = 0.0
        val complianceFloat = ((onTime + slow * 0.5f) / graded) * 100f
        val compliancePercentage = complianceFloat.toInt().coerceIn(0, 100)

        val gradeStr = when {
            compliancePercentage >= 90 -> "A"
            compliancePercentage >= 80 -> "B"
            compliancePercentage >= 70 -> "C"
            compliancePercentage >= 60 -> "D"
            else -> "F"
        }

        return AuditGrade(graded, onTime, slow, missed, open, compliancePercentage, gradeStr)
    }

    /** Convenience: chain, grade, and score a set of alert rows in one go. */
    fun calculateComplianceScore(audits: List<NotificationAudit>, now: Long = System.currentTimeMillis()): AuditGrade =
        gradeTasks(buildTasks(audits, now))

    fun isSameDay(a: Long, b: Long): Boolean = dayStart(a) == dayStart(b)

    /** "22m", "1h", "1h 40m". */
    fun formatMinutes(mins: Long): String = when {
        mins < 60L -> "${mins}m"
        mins % 60L == 0L -> "${mins / 60L}h"
        else -> "${mins / 60L}h ${mins % 60L}m"
    }

    private fun dayStart(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun nextDayStart(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = dayStart(millis)
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
}
