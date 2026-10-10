package io.github.maximus5.ffnotifyimporter.prefs

import android.content.Context

/**
 * Whether the first-run setup checklist on the Messages screen is retired for good. Set the first
 * time any message is captured (or the user hides it) and never cleared - an empty Messages list
 * is also what a user sees after retention expires every captured message, and the checklist must
 * not come back then.
 */
class SetupChecklistPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isRetired(): Boolean = prefs.getBoolean(KEY_RETIRED, false)

    fun retire() {
        if (!isRetired()) prefs.edit().putBoolean(KEY_RETIRED, true).apply()
    }

    private companion object {
        const val PREFS_NAME = "setup_checklist"
        const val KEY_RETIRED = "retired"
    }
}
