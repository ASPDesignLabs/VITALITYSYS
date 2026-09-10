package com.snakesan.vitalitysys

import android.content.Intent
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class VitalityDataService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path

                // When the Phone pushes calculated status to the Watch
                if (path == "/vitality_status") {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap

                    // Extract all data with safe fallbacks
                    val hp = dataMap.getInt("user_hp", 100)
                    val hydStatus = dataMap.getInt("hyd_status", 1)
                    val mealStatus = dataMap.getInt("meal_status", 0)
                    val overcharge = dataMap.getInt("overcharge", 0)

                    // --- BROADCAST THE FULL PAYLOAD TO OVERSEER ---
                    val intent = Intent("com.snakesan.overseer.UPDATE_STATUS").apply {
                        setPackage("com.snakesan.overseer")
                        putExtra("source_app", "VITALITY")
                        putExtra("hp", hp)
                        putExtra("hyd_status", hydStatus)
                        putExtra("meal_status", mealStatus)
                        putExtra("overcharge", overcharge) // Pass the new metric!
                    }

                    sendBroadcast(intent)

                    android.util.Log.d(
                        "VITALITY_SERVICE",
                        "Background Relay to HUD: HP:$hp OC:$overcharge"
                    )
                }
            }
        }
    }
}