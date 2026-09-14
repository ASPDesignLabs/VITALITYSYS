package com.snakesan.vitalitysys.debug

import com.snakesan.vitalitysys.MainActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// All phone-side debug/data-management tools live here, isolated from the
// real app flow. Every entry point checks DebugFlags.isEnabled() itself so
// this stays inert even if something calls it outside DebugPanel's UI.

fun MainActivity.deleteLastHour() {
    if (!DebugFlags.isEnabled(this)) return
    lifecycleScope.launch(Dispatchers.IO) {
        db.systemDao().deleteLogsSince(System.currentTimeMillis() - 3600000L)
    }
}

fun MainActivity.deleteLast24Hours() {
    if (!DebugFlags.isEnabled(this)) return
    lifecycleScope.launch(Dispatchers.IO) {
        db.systemDao().deleteLogsSince(System.currentTimeMillis() - 86400000L)
    }
}

fun MainActivity.deleteCustomRange() {
    if (!DebugFlags.isEnabled(this)) return
    lifecycleScope.launch(Dispatchers.IO) {
        db.systemDao().deleteLogsInWindow(rangeStart, rangeEnd)
    }
}

fun MainActivity.wipeAllData() {
    if (!DebugFlags.isEnabled(this)) return
    lifecycleScope.launch(Dispatchers.IO) { db.systemDao().nukeAllLogs() }
}
