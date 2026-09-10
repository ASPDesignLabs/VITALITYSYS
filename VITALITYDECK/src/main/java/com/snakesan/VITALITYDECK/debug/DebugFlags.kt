package com.snakesan.vitalitysys.debug

import android.content.Context

// Runtime switch for the watch's debug/testing tools (force-triggering a
// sentinel alert, injecting overcharge). Off by default so a long-press on
// the protocol display behaves as a normal action during day-to-day use;
// long-press the header text to flip this on for testing, then flip it
// back off when you're done.
object DebugFlags {
    private const val PREFS = "vitality_watch_debug"
    private const val KEY_ENABLED = "debug_tools_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun toggle(context: Context): Boolean {
        val newValue = !isEnabled(context)
        setEnabled(context, newValue)
        return newValue
    }
}
