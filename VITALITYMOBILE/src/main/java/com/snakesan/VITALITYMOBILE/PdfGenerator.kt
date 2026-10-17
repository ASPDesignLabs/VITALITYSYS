package com.snakesan.vitalitysys

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.snakesan.vitalitysys.data.NotificationAudit
import com.snakesan.vitalitysys.data.SystemLog
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object PdfGenerator {

    data class PdfTheme(
        val bgColor: Int, val textColor: Int, val accentColor: Int,
        val gridColor: Int, val isDark: Boolean
    )

    fun generateReport(
        context: Context,
        logs: List<SystemLog>,
        audits: List<NotificationAudit>,
        isClinical: Boolean
    ): android.net.Uri? {
        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842

        val theme = if (isClinical) {
            PdfTheme(Color.WHITE, Color.BLACK, Color.DKGRAY, Color.LTGRAY, false)
        } else {
            PdfTheme(Color.rgb(5, 5, 5), Color.WHITE, Color.rgb(255, 0, 85), Color.rgb(40, 40, 40), true)
        }

        val bgPaint = Paint().apply { color = theme.bgColor; style = Paint.Style.FILL }
        val textPaint = Paint().apply { color = theme.textColor; textSize = 10f; isAntiAlias = true }
        val headerPaint = Paint().apply { color = theme.accentColor; textSize = 20f; isAntiAlias = true; isFakeBoldText = true }
        val subheadPaint = Paint().apply { color = theme.accentColor; textSize = 12f; isAntiAlias = true; isFakeBoldText = true }
        val boldTextPaint = Paint().apply { color = theme.textColor; textSize = 10f; isAntiAlias = true; isFakeBoldText = true }
        val redTextPaint = Paint().apply { color = Color.RED; textSize = 10f; isAntiAlias = true; isFakeBoldText = true }
        val gridPaint = Paint().apply { color = theme.gridColor; strokeWidth = 1f }
        val barPaint = Paint().apply { color = theme.accentColor; style = Paint.Style.FILL }

        var pageNumber = 1
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var yPos = 60f

        fun newPage() {
            page?.let { document.finishPage(it) }
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            pageNumber++
            page = document.startPage(pageInfo)
            canvas = page!!.canvas
            canvas!!.drawRect(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat(), bgPaint)
            yPos = 60f
        }

        fun checkPage(neededSpace: Float) {
            if (yPos + neededSpace > pageHeight - 50f) newPage()
        }

        // --- PAGE 1: DIAGNOSTIC & EVENT LOGS ---
        newPage()
        val title = if (isClinical) "VITALITY CLINICAL REPORT" else "VITALITY.SYS // DIAGNOSTIC_DUMP"
        canvas!!.drawText(title, 40f, yPos, headerPaint)
        yPos += 20f
        
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        textPaint.textSize = 8f
        canvas!!.drawText("Generated: $dateStr", 40f, yPos, textPaint)
        textPaint.textSize = 10f
        yPos += 40f

        canvas!!.drawText("EVENT LOG (PAIN & MANUAL ENTRIES)", 40f, yPos, subheadPaint)
        yPos += 20f
        canvas!!.drawText("DATE/TIME", 40f, yPos, boldTextPaint)
        canvas!!.drawText("TYPE", 150f, yPos, boldTextPaint)
        canvas!!.drawText("LVL", 220f, yPos, boldTextPaint)
        canvas!!.drawText("NOTES", 260f, yPos, boldTextPaint)
        yPos += 10f
        canvas!!.drawLine(40f, yPos, 555f, yPos, gridPaint)
        yPos += 20f

        val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.US)
        logs.reversed().take(30).forEach { log ->
            checkPage(30f)
            canvas!!.drawText(dateFormat.format(Date(log.timestamp)), 40f, yPos, textPaint)
            canvas!!.drawText(log.type, 150f, yPos, textPaint)
            val lvlPaint = if (log.value > 7) redTextPaint else textPaint
            canvas!!.drawText("${log.value}", 220f, yPos, lvlPaint)
            val note = log.note ?: ""
            canvas!!.drawText(if (note.length > 45) note.substring(0, 42) + "..." else note, 260f, yPos, textPaint)
            yPos += 20f
        }

        // --- PAGE 2: BEHAVIORAL ANALYSIS & TIME SUMMARIES ---
        // Everything on this page counts TASKS, not alerts. The watch re-alerts
        // the same overdue item about every 15 minutes and each alert is its
        // own audit row, so counting rows charged one late dose several times
        // and disagreed with the in-app grade. VitalityMath.buildTasks chains
        // those rows back into one task each. Wording is deliberately plain
        // (on time / late / not done) in both report themes.
        newPage()
        canvas!!.drawText("BEHAVIORAL TELEMETRY & TIMELINESS ANALYSIS", 40f, yPos, headerPaint)
        yPos += 30f

        val now = System.currentTimeMillis()
        val tasks = VitalityMath.buildTasks(audits, now)

        textPaint.textSize = 8f
        canvas!!.drawText("A task is one dose, meal, drink or routine; repeated alerts for it count once.", 40f, yPos, textPaint)
        yPos += 11f
        canvas!!.drawText("On time = done within ${VitalityMath.ON_TIME_MINUTES} min of the first alert.  Late = done later the same day.", 40f, yPos, textPaint)
        yPos += 11f
        canvas!!.drawText("Not done = not done by midnight.  Still-open tasks (day not over) are not counted in the percentages.", 40f, yPos, textPaint)
        textPaint.textSize = 10f
        yPos += 30f

        // 1. TIME GATED SUMMARIES (Daily, Weekly, Monthly)
        canvas!!.drawText("ACTIVITY AGGREGATES", 40f, yPos, subheadPaint)
        yPos += 20f

        val dayMs = 86400000L

        fun printSummaryRow(label: String, windowMs: Long, y: Float) {
            val windowTasks = tasks.filter { it.firstIssued > now - windowMs }
            val alertsFired = audits.count { it.timestampIssued > now - windowMs }
            val g = VitalityMath.gradeTasks(windowTasks)
            val onTimePct = if (g.gradedTasks > 0) g.onTimeCount * 100 / g.gradedTasks else null
            canvas!!.drawText(label, 40f, y, boldTextPaint)
            canvas!!.drawText("Tasks: ${windowTasks.size}", 125f, y, textPaint)
            canvas!!.drawText("On time: ${onTimePct?.let { "$it%" } ?: "-"}", 195f, y,
                if (onTimePct != null && onTimePct < 70) redTextPaint else textPaint)
            canvas!!.drawText("Late: ${g.slowCount}", 285f, y, textPaint)
            canvas!!.drawText("Not done: ${g.missedCount}", 340f, y, textPaint)
            canvas!!.drawText("Alerts fired: $alertsFired", 430f, y, textPaint)
        }

        printSummaryRow("24H SUMMARY", dayMs, yPos); yPos += 20f
        printSummaryRow("7D SUMMARY", 7 * dayMs, yPos); yPos += 20f
        printSummaryRow("30D SUMMARY", 30 * dayMs, yPos); yPos += 40f

        // 2. PROTOCOL BREAKDOWN & TIME TO FINISH
        canvas!!.drawText("PROTOCOL TIMELINESS & AVERAGE TIME TO FINISH", 40f, yPos, subheadPaint)
        yPos += 20f

        canvas!!.drawText("CATEGORY", 40f, yPos, boldTextPaint)
        canvas!!.drawText("TASKS", 150f, yPos, boldTextPaint)
        canvas!!.drawText("ON TIME", 200f, yPos, boldTextPaint)
        canvas!!.drawText("AVG TIME TO FINISH", 270f, yPos, boldTextPaint)
        canvas!!.drawText("NOT DONE", 385f, yPos, boldTextPaint)
        canvas!!.drawText("GRADE", 460f, yPos, boldTextPaint)
        yPos += 10f
        canvas!!.drawLine(40f, yPos, 555f, yPos, gridPaint)
        yPos += 20f

        tasks.groupBy { it.protocolType }.toList().sortedBy { it.first }.forEach { (proto, protoTasks) ->
            val g = VitalityMath.gradeTasks(protoTasks)
            val onTimePct = if (g.gradedTasks > 0) g.onTimeCount * 100 / g.gradedTasks else null
            // Average over tasks that were actually finished in time to count
            // (on time or late) — a task finished only after midnight isn't an
            // honest "time to finish".
            val finishTimes = protoTasks
                .filter { it.outcome == VitalityMath.TaskOutcome.ON_TIME || it.outcome == VitalityMath.TaskOutcome.SLOW }
                .mapNotNull { it.finishedAfterMins }
            val avgFinish = if (finishTimes.isNotEmpty()) VitalityMath.formatMinutes(finishTimes.average().toLong()) else "-"

            canvas!!.drawText(protocolDisplayLabel(proto), 40f, yPos, textPaint)
            canvas!!.drawText("${protoTasks.size}", 150f, yPos, textPaint)
            canvas!!.drawText(onTimePct?.let { "$it%" } ?: "-", 200f, yPos,
                if (onTimePct != null && onTimePct < 70) redTextPaint else textPaint)
            canvas!!.drawText(avgFinish, 270f, yPos, textPaint)
            canvas!!.drawText("${g.missedCount}", 385f, yPos, textPaint)
            canvas!!.drawText(g.grade, 460f, yPos, boldTextPaint)

            yPos += 20f
        }
        yPos += 20f

        // 3. WHEN TASKS RUN LATE OR GO UNDONE
        canvas!!.drawText("WHEN TASKS RUN LATE OR GO UNDONE", 40f, yPos, subheadPaint)
        yPos += 20f

        val cal = Calendar.getInstance()
        val troubleByHour = tasks
            .filter { it.outcome == VitalityMath.TaskOutcome.SLOW || it.outcome == VitalityMath.TaskOutcome.MISSED }
            .groupBy { cal.timeInMillis = it.firstIssued; cal.get(Calendar.HOUR_OF_DAY) }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }

        if (troubleByHour.isEmpty()) {
            canvas!!.drawText("No significant time-of-day pattern detected.", 40f, yPos, textPaint)
        } else {
            val worstHour = troubleByHour.first()
            val timeString = String.format("%02d:00 - %02d:00", worstHour.first, (worstHour.first + 1) % 24)
            canvas!!.drawText("Most late or not-done tasks start in this window: $timeString (${worstHour.second} tasks)", 40f, yPos, redTextPaint)
            yPos += 15f
            canvas!!.drawText("Recommendation: Adjust routine or reminders around this window.", 40f, yPos, textPaint)
        }
        yPos += 50f

        // 4. TIME TO FINISH HISTOGRAM
        checkPage(200f)
        canvas!!.drawText("TIME TO FINISH (ALL PROTOCOLS)", 40f, yPos, subheadPaint)
        yPos += 30f

        var within15 = 0; var within60 = 0; var over60 = 0; var notDone = 0
        tasks.forEach {
            when (it.outcome) {
                VitalityMath.TaskOutcome.ON_TIME -> if ((it.finishedAfterMins ?: 0L) <= 15L) within15++ else within60++
                VitalityMath.TaskOutcome.SLOW -> over60++
                VitalityMath.TaskOutcome.MISSED -> notDone++
                VitalityMath.TaskOutcome.OPEN -> Unit // not over yet: not counted
            }
        }
        val stillOpen = tasks.count { it.outcome == VitalityMath.TaskOutcome.OPEN }

        val maxCount = listOf(within15, within60, over60, notDone).maxOrNull()?.coerceAtLeast(1) ?: 1
        val histHeight = 100f
        val histWidth = 400f
        val startX = 60f
        val bottomY = yPos + histHeight

        // Draw axes
        canvas!!.drawLine(startX, yPos, startX, bottomY, gridPaint)
        canvas!!.drawLine(startX, bottomY, startX + histWidth, bottomY, gridPaint)

        // Draw Bars
        val buckets = listOf(
            Pair("0-15m", within15),
            Pair("15-60m", within60),
            Pair("Over 60m", over60),
            Pair("Not done", notDone)
        )

        val barWidth = 60f
        val spacing = 30f
        var currentX = startX + 20f

        buckets.forEach { (label, count) ->
            val height = (count.toFloat() / maxCount) * histHeight
            val top = bottomY - height

            // Make the "Not done" bar red
            val p = if (label == "Not done") Paint().apply { color = Color.RED } else barPaint

            canvas!!.drawRect(RectF(currentX, top, currentX + barWidth, bottomY), p)
            canvas!!.drawText("$count", currentX + 20f, top - 10f, boldTextPaint)
            canvas!!.drawText(label, currentX, bottomY + 15f, textPaint)

            currentX += barWidth + spacing
        }

        if (stillOpen > 0) {
            textPaint.textSize = 8f
            canvas!!.drawText("$stillOpen task(s) are still open (the day isn't over) and are not shown.", startX, bottomY + 32f, textPaint)
            textPaint.textSize = 10f
        }

        yPos = bottomY + 50f

        page?.let { document.finishPage(it) }

        // --- SAVE FILE ---
        val fileName = if(isClinical) "Vitality_Clinical_Report.pdf" else "Vitality_Behavioral_Audit.pdf"
        val file = File(context.cacheDir, fileName)
        try {
            document.writeTo(FileOutputStream(file))
            document.close()
            return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            e.printStackTrace()
            document.close()
            return null
        }
    }
}
