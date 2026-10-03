package io.github.maximus5.ffnotifyimporter.data

/**
 * Wires [MessageStore] up to [MessageHistoryDb] according to the user's retention setting (see
 * `prefs.RetentionPrefs`). Split into a startup path and a setting-changed path on purpose:
 * [MessageStore.restore] has no dedup against what's already in memory, so it must only ever run
 * once, against an empty store, right after process start - never again when the user tweaks the
 * setting mid-session, or already-shown messages would be duplicated.
 */
object RetentionController {

    const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

    fun cutoffMillis(nowMillis: Long, retentionDays: Int): Long =
        nowMillis - retentionDays * MILLIS_PER_DAY

    /** Call once, e.g. from `Application.onCreate`. */
    fun applyOnStartup(
        retentionDays: Int,
        store: MessageStore,
        db: MessageHistoryDb,
        now: () -> Long = System::currentTimeMillis,
    ) {
        if (retentionDays <= 0) {
            db.clearAll()
            return
        }
        store.restore(db.loadNewerThan(cutoffMillis(now(), retentionDays)))
        wirePersistence(retentionDays, store, db, now)
    }

    /** Call whenever the user changes the retention setting while the app is already running. */
    fun applyOnSettingChanged(
        retentionDays: Int,
        store: MessageStore,
        db: MessageHistoryDb,
        now: () -> Long = System::currentTimeMillis,
    ) {
        if (retentionDays <= 0) {
            store.onNewMessage = null
            store.onMessageRemoved = null
            db.clearAll()
            return
        }
        db.deleteOlderThan(cutoffMillis(now(), retentionDays))
        wirePersistence(retentionDays, store, db, now)
    }

    private fun wirePersistence(
        retentionDays: Int,
        store: MessageStore,
        db: MessageHistoryDb,
        now: () -> Long,
    ) {
        store.onNewMessage = { message ->
            db.insert(message)
            db.deleteOlderThan(cutoffMillis(now(), retentionDays))
        }
        store.onMessageRemoved = { message -> db.delete(message) }
    }
}
