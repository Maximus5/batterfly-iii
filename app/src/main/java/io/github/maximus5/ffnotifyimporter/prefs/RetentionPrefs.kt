package io.github.maximus5.ffnotifyimporter.prefs

import android.content.Context

/**
 * How many days of captured messages to mirror to local on-disk storage (see
 * `data.MessageHistoryDb`). 0 means "don't persist anything" - the original in-memory-only,
 * nothing-written-to-disk behavior - and is the default; the user has to opt in.
 */
class RetentionPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getRetentionDays(): Int = prefs.getInt(KEY_RETENTION_DAYS, 0)

    fun setRetentionDays(days: Int) {
        require(days in ALLOWED_VALUES) { "Unsupported retention: $days" }
        prefs.edit().putInt(KEY_RETENTION_DAYS, days).apply()
    }

    companion object {
        val ALLOWED_VALUES = setOf(0, 1, 3, 5, 7)

        private const val PREFS_NAME = "retention"
        private const val KEY_RETENTION_DAYS = "retention_days"
    }
}
