package io.github.maximus5.ffnotifyimporter.data

import io.github.maximus5.ffnotifyimporter.prefs.RetentionSettings
import io.github.maximus5.ffnotifyimporter.rules.CreatedTransactionsDb

/**
 * Applies the user's history setting (see `prefs.RetentionSettings`) to every append-only store:
 * the in-memory [MessageStore], its optional on-disk mirror [MessageHistoryDb], and the dedup log
 * [CreatedTransactionsDb]. The retention window always applies; only the on-disk mirror of the
 * message list is optional. Split into a startup path and a setting-changed path on purpose:
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
        settings: RetentionSettings,
        store: MessageStore,
        db: MessageHistoryDb,
        createdTransactions: CreatedTransactionsDb,
        now: () -> Long = System::currentTimeMillis,
    ) {
        val cutoff = cutoffMillis(now(), settings.days)
        if (settings.persistToDisk) {
            db.deleteOlderThan(cutoff)
            store.restore(db.loadNewerThan(cutoff))
        } else {
            db.clearAll()
        }
        createdTransactions.deleteOlderThan(cutoff)
        wire(settings, store, db, now)
    }

    /** Call whenever the user changes the setting while the app is already running. */
    fun applyOnSettingChanged(
        settings: RetentionSettings,
        store: MessageStore,
        db: MessageHistoryDb,
        createdTransactions: CreatedTransactionsDb,
        now: () -> Long = System::currentTimeMillis,
    ) {
        val cutoff = cutoffMillis(now(), settings.days)
        store.deleteOlderThan(cutoff)
        createdTransactions.deleteOlderThan(cutoff)
        db.clearAll()
        if (settings.persistToDisk) {
            // Memory is always a superset of what's on disk (loaded at startup, every addition
            // mirrored), so rewriting the disk copy from it is the one correct move for both "just
            // switched on" (nothing on disk yet) and "window changed" (older rows already gone).
            store.snapshot().asReversed().forEach { db.insert(it) }
        }
        wire(settings, store, db, now)
    }

    /** After a successful create - the dedup log is bounded by the same window as everything else. */
    fun purgeCreatedTransactions(
        createdTransactions: CreatedTransactionsDb,
        retentionDays: Int,
        now: () -> Long = System::currentTimeMillis,
    ) {
        createdTransactions.deleteOlderThan(cutoffMillis(now(), retentionDays))
    }

    private fun wire(
        settings: RetentionSettings,
        store: MessageStore,
        db: MessageHistoryDb,
        now: () -> Long,
    ) {
        store.onNewMessage = { message ->
            val cutoff = cutoffMillis(now(), settings.days)
            if (settings.persistToDisk) {
                db.insert(message)
                db.deleteOlderThan(cutoff)
            }
            store.deleteOlderThan(cutoff, keep = message)
        }
        store.onMessageRemoved = if (settings.persistToDisk) { message -> db.delete(message) } else null
        store.onMessageUpdated = if (settings.persistToDisk) { message -> db.updateTransactionId(message) } else null
    }
}
