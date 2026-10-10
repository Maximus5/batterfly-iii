package io.github.maximus5.ffnotifyimporter.prefs

import android.content.Context

/** The two halves of the history setting: how long this app's own append-only data (captured
 * messages, the created-transactions dedup log) is kept, and whether captured messages are also
 * mirrored to on-disk storage so they survive an app/device restart. */
data class RetentionSettings(val persistToDisk: Boolean, val days: Int) {

    companion object {
        const val DEFAULT_DAYS = 5
        val ALLOWED_DAYS = setOf(1, 3, 5, 7, 30)

        fun normalizeDays(days: Int): Int = if (days in ALLOWED_DAYS) days else DEFAULT_DAYS

        /** Maps the old single "retention days" value (0 = don't save, otherwise on-disk for N
         * days) onto the two-part setting, preserving whatever the install already had. */
        fun fromLegacyDays(legacyDays: Int): RetentionSettings =
            RetentionSettings(persistToDisk = legacyDays > 0, days = normalizeDays(legacyDays))
    }
}

/**
 * [isPersistToDisk] (default off - captured messages live in memory only and are lost on an app or
 * device restart) and [getRetentionDays] (default 5, always one of [RetentionSettings.ALLOWED_DAYS])
 * - the window applies to everything this app appends over time, in memory and on disk alike.
 */
class RetentionPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacy()
    }

    fun isPersistToDisk(): Boolean = prefs.getBoolean(KEY_PERSIST_TO_DISK, false)

    fun setPersistToDisk(persist: Boolean) {
        prefs.edit().putBoolean(KEY_PERSIST_TO_DISK, persist).apply()
    }

    fun getRetentionDays(): Int =
        RetentionSettings.normalizeDays(prefs.getInt(KEY_RETENTION_DAYS, RetentionSettings.DEFAULT_DAYS))

    fun setRetentionDays(days: Int) {
        require(days in RetentionSettings.ALLOWED_DAYS) { "Unsupported retention: $days" }
        prefs.edit().putInt(KEY_RETENTION_DAYS, days).apply()
    }

    fun get(): RetentionSettings = RetentionSettings(isPersistToDisk(), getRetentionDays())

    fun set(settings: RetentionSettings) {
        prefs.edit()
            .putBoolean(KEY_PERSIST_TO_DISK, settings.persistToDisk)
            .putInt(KEY_RETENTION_DAYS, RetentionSettings.normalizeDays(settings.days))
            .apply()
    }

    private fun migrateLegacy() {
        if (prefs.contains(KEY_PERSIST_TO_DISK) || !prefs.contains(LEGACY_KEY_RETENTION_DAYS)) return
        val migrated = RetentionSettings.fromLegacyDays(prefs.getInt(LEGACY_KEY_RETENTION_DAYS, 0))
        prefs.edit()
            .putBoolean(KEY_PERSIST_TO_DISK, migrated.persistToDisk)
            .putInt(KEY_RETENTION_DAYS, migrated.days)
            .remove(LEGACY_KEY_RETENTION_DAYS)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "retention"
        const val KEY_PERSIST_TO_DISK = "persist_to_disk"
        const val KEY_RETENTION_DAYS = "window_days"
        const val LEGACY_KEY_RETENTION_DAYS = "retention_days"
    }
}
