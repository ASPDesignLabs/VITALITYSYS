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
        newPage()
        canvas!!.drawText("BEHAVIORAL TELEMETRY & SLIPPAGE ANALYSIS", 40f, yPos, headerPaint)
        yPos += 40f

        // 1. TIME GATED SUMMARIES (Daily, Weekly, Monthly)
        canvas!!.drawText("ACTIVITY AGGREGATES", 40f, yPos, subheadPaint)
        yPos += 20f
        
        val now = System.currentTimeMillis()
        val dayMs = 86400000L
        val weekAudits = audits.filter { it.timestampIssued > now - (7 * dayMs) }
        val monthAudits = audits.filter { it.timestampIssued > now - (30 * dayMs) }
        val dayAudits = audits.filter { it.timestampIssued > now - dayMs }

        fun printSummaryRow(label: String, data: List<NotificationAudit>, y: Float) {
            val total = data.size
            val fails = data.count { it.finalStatus == "ABANDONED" || it.interactionType == "DISMISSED" }
            val failRate = if(total > 0) (fails.toFloat() / total * 100).toInt() else 0
            canvas!!.drawText(label, 40f, y, boldTextPaint)
            canvas!!.drawText("Alerts: $total", 160f, y, textPaint)
            canvas!!.drawText("Slippage: $failRate%", 260f, y, if(failRate > 30) redTextPaint else textPaint)
        }

        printSummaryRow("24H SUMMARY", dayAudits, yPos); yPos += 20f
        printSummaryRow("7D SUMMARY", weekAudits, yPos); yPos += 20f
        printSummaryRow("30D SUMMARY", monthAudits, yPos); yPos += 40f

        // 2. PROTOCOL BREAKDOWN & LATENCY
        canvas!!.drawText("PROTOCOL SLIPPAGE & AVERAGE LATENCY", 40f, yPos, subheadPaint)
        yPos += 20f
        
        canvas!!.drawText("CATEGORY", 40f, yPos, boldTextPaint)
        canvas!!.drawText("AVG DELAY", 160f, yPos, boldTextPaint)
        canvas!!.drawText("SLIP RATE", 260f, yPos, boldTextPaint)
        canvas!!.drawText("STATUS", 360f, yPos, boldTextPaint)
        yPos += 10f
        canvas!!.drawLine(40f, yPos, 555f, yPos, gridPaint)
        yPos += 20f

        val grouped = audits.groupBy { it.protocolType }
        grouped.forEach { (proto, protoAudits) ->
            val total = protoAudits.size
            // Calculate delay for interacted items
            val interacted = protoAudits.filter { it.timestampInteracted != null }
            val avgDelayMins = if (interacted.isNotEmpty()) {
                interacted.map { (it.timestampInteracted!! - it.timestampIssued) / 60000L }.average().toInt()
            } else 0
            
            // Slippage (Ignored + Warnings)
            val slipped = protoAudits.count { 
                it.finalStatus == "ABANDONED" || 
                it.interactionType == "DISMISSED" || 
                it.interactionType == "IGNORED" 
            }
            val slipRate = if(total > 0) (slipped.toFloat() / total * 100).toInt() else 0

            canvas!!.drawText(proto, 40f, yPos, textPaint)
            canvas!!.drawText("${avgDelayMins}m", 160f, yPos, textPaint)
            canvas!!.drawText("$slipRate%", 260f, yPos, if(slipRate > 25) redTextPaint else textPaint)
            
            val status = if (slipRate > 50) "CRITICAL RESISTANCE" else if (slipRate > 25) "DEGRADING" else "STABLE"
            canvas!!.drawText(status, 360f, yPos, if(slipRate > 25) redTextPaint else boldTextPaint)
            
            yPos += 20f
        }
        yPos += 20f

        // 3. NOTED RESISTANCE MAPPING
        canvas!!.drawText("NOTED RESISTANCE MAPPING", 40f, yPos, subheadPaint)
        yPos += 20f

        val cal = Calendar.getInstance()
        val failuresByHour = audits.filter { it.finalStatus == "ABANDONED" || it.interactionType == "DISMISSED" }
            .groupBy { cal.timeInMillis = it.timestampIssued; cal.get(Calendar.HOUR_OF_DAY) }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }

        if (failuresByHour.isEmpty()) {
            canvas!!.drawText("No significant time-based resistance detected.", 40f, yPos, textPaint)
        } else {
            val worstHour = failuresByHour.first()
            val timeString = String.format("%02d:00 - %02d:00", worstHour.first, (worstHour.first + 1) % 24)
            canvas!!.drawText("Highest Notification Abandonment Block: $timeString", 40f, yPos, redTextPaint)
            yPos += 15f
            canvas!!.drawText("Recommendation: Adjust routine or mute non-critical alerts during this window.", 40f, yPos, textPaint)
        }
        yPos += 50f

        // 4. RESPONSE TIME HISTOGRAM
        checkPage(200f)
        canvas!!.drawText("RESPONSE LATENCY HISTOGRAM (ALL PROTOCOLS)", 40f, yPos, subheadPaint)
        yPos += 30f

        var countUnder5 = 0; var countUnder15 = 0; var countUnder60 = 0; var countIgnored = 0
        audits.forEach {
            if (it.timestampInteracted == null || it.finalStatus == "ABANDONED") { countIgnored++ } 
            else {
                val delay = (it.timestampInteracted!! - it.timestampIssued) / 60000L
                when {
                    delay <= 5 -> countUnder5++
                    delay <= 15 -> countUnder15++
                    else -> countUnder60++
                }
            }
        }

        val maxCount = listOf(countUnder5, countUnder15, countUnder60, countIgnored).maxOrNull()?.coerceAtLeast(1) ?: 1
        val histHeight = 100f
        val histWidth = 400f
        val startX = 60f
        val bottomY = yPos + histHeight
        
        // Draw axes
        canvas!!.drawLine(startX, yPos, startX, bottomY, gridPaint)
        canvas!!.drawLine(startX, bottomY, startX + histWidth, bottomY, gridPaint)

        // Draw Bars
        val buckets = listOf(
            Pair("< 5m (Ideal)", countUnder5),
            Pair("5-15m (Delay)", countUnder15),
            Pair("15-60m (Late)", countUnder60),
            Pair("Ignored/Swipe", countIgnored)
        )
        
        val barWidth = 60f
        val spacing = 30f
        var currentX = startX + 20f

        buckets.forEach { (label, count) ->
            val height = (count.toFloat() / maxCount) * histHeight
            val top = bottomY - height
            
            // Make "Ignored" bar Red
            val p = if(label.startsWith("Ignored")) Paint().apply{color=Color.RED} else barPaint
            
            canvas!!.drawRect(RectF(currentX, top, currentX + barWidth, bottomY), p)
            canvas!!.drawText("$count", currentX + 20f, top - 10f, boldTextPaint)
            canvas!!.drawText(label, currentX, bottomY + 15f, textPaint)
            
            currentX += barWidth + spacing
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
