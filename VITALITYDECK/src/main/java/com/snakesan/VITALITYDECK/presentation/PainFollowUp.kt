package com.snakesan.vitalitysys

import android.app.NotificationManager
import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

// The ONE place the pain follow-up rule lives: logging pain above 3 schedules a
// 60-minute check-in, logging 3 or below cancels any pending one (the sleep
// window and clinical override are applied when the check-in fires, in
// SentinelWorker). It is called both for pain logged on the watch
// (MainActivity.logPain) and for pain logged on the phone, which tells the
// watch through VitalityDataService — a background listener, because the watch
// app is usually closed when the phone is used. The watch owns this schedule so
// there is exactly one scheduler, however and wherever pain was logged.
object PainFollowUp {
    private const val WORK_NAME = "PainCheck"

    fun apply(context: Context, level: Int) {
        VitalityStore(context).lastPainLevel = level

        // Logging pain is the answer to a pending check-in, so clear it
        // (it travels under its own id; see PAIN_PROTOCOL_ID).
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(PAIN_PROTOCOL_ID)

        val work = WorkManager.getInstance(context)
        if (level > 3) {
            val painCheck = OneTimeWorkRequestBuilder<SentinelWorker>()
                .setInitialDelay(60, TimeUnit.MINUTES)
                .setInputData(Data.Builder().putBoolean("CHECK_PAIN", true).build())
                .build()
            work.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, painCheck)
        } else {
            work.cancelUniqueWork(WORK_NAME)
        }
    }
}
