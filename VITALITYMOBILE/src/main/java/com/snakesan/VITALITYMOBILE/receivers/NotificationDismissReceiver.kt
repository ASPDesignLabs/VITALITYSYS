package com.snakesan.vitalitysys.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.snakesan.vitalitysys.MainActivity
import com.snakesan.vitalitysys.data.VitalityDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notifId = intent.getIntExtra("NOTIF_ID", -1)
        if (notifId == -1) return

        // A full-screen-intent alert can auto-launch the app without an
        // explicit tap, leaving the original notification sitting in the
        // shade even while the user is already actively responding to it
        // in-app. Swiping that leftover away then is tidying up, not
        // abandoning — don't record it as a missed response.
        if (MainActivity.isActivelyRespondingTo(notifId)) return

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
