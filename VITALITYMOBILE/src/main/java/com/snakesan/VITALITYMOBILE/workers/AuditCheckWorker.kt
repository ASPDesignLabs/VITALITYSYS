package com.snakesan.vitalitysys.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.snakesan.vitalitysys.data.VitalityDatabase

class AuditCheckWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val notifId = inputData.getInt("NOTIF_ID", -1)
        if (notifId == -1) return Result.failure()

        val db = VitalityDatabase.getDatabase(applicationContext)
        val audit = db.systemDao().getAuditByNotificationId(notifId)

        // If 5 minutes have passed and no interaction has been logged, flag as IGNORED
        if (audit != null && audit.interactionType == null) {
            db.systemDao().updateAudit(
                audit.copy(
                    interactionType = "IGNORED",
                    finalStatus = "ABANDONED"
                )
            )
        }
        return Result.success()
    }
}
