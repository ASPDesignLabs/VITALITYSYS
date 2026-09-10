package com.snakesan.vitalitysys.debug

import android.content.Context

// Runtime switch for the phone's ADMIN debug/data-management tools (fuzzy
// data injection, overcharge injection, quick purge, surgical deletion,
// factory reset). Off by default so none of it shows up or can fire during
// normal day-to-day use; long-press the ADMIN header in DebugPanel to flip
// it on for testing, then flip it back off when you're done.
object DebugFlags {
    private const val PREFS = "vitality_debug"
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
