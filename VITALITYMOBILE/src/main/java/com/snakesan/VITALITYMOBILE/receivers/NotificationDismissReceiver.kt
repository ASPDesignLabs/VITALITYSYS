package com.snakesan.vitalitysys.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.snakesan.vitalitysys.data.VitalityDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notifId = intent.getIntExtra("NOTIF_ID", -1)
        if (notifId == -1) return

        CoroutineScope(Dispatchers.IO).launch {
            val db = VitalityDatabase.getDatabase(context)
            val audit = db.systemDao().getAuditByNotificationId(notifId)
            
            if (audit != null && audit.interactionType == null) {
                db.systemDao().updateAudit(
                    audit.copy(
                        timestampInteracted = System.currentTimeMillis(),
                        interactionType = "DISMISSED",
                        finalStatus = "ABANDONED"
                    )
                )
            }
        }
    }
}
