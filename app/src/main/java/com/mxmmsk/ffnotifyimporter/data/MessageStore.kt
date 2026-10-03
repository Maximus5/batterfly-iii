package com.mxmmsk.ffnotifyimporter.data

/**
 * Keeps captured notifications in memory only - nothing is ever written to disk or sent
 * anywhere. By design this is lost on process death (app swiped away while backgrounded,
 * low-memory kill, or device reboot); see AGENTS.md/TODO.md for that trade-off.
 *
 * Identical (packageName, title, text) notifications arriving within [dedupWindowMillis] of
 * the first occurrence are collapsed into a single entry - banks commonly re-post the exact
 * same notification a few times in quick succession.
 */
class MessageStore(
    private val dedupWindowMillis: Long = DEFAULT_DEDUP_WINDOW_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val messages = mutableListOf<CapturedMessage>()
    private val listeners = mutableListOf<(List<CapturedMessage>) -> Unit>()

    /** Returns true if a new entry was recorded, false if it was dropped as a duplicate. */
    fun add(packageName: String, appLabel: String, title: String, text: String): Boolean {
        val now = clock()
        val snapshot: List<CapturedMessage>
        synchronized(lock) {
            val isDuplicate = messages.any { existing ->
                existing.packageName == packageName &&
                    existing.title == title &&
                    existing.text == text &&
                    now - existing.timestampMillis <= dedupWindowMillis
            }
            if (isDuplicate) return false
            messages.add(0, CapturedMessage(packageName, appLabel, title, text, now))
            snapshot = messages.toList()
        }
        listeners.forEach { it(snapshot) }
        return true
    }

    fun snapshot(): List<CapturedMessage> = synchronized(lock) { messages.toList() }

    /** Invokes [listener] immediately with the current snapshot, then on every future change. */
    fun addListener(listener: (List<CapturedMessage>) -> Unit) {
        synchronized(lock) { listeners.add(listener) }
        listener(snapshot())
    }

    fun removeListener(listener: (List<CapturedMessage>) -> Unit) {
        synchronized(lock) { listeners.remove(listener) }
    }

    companion object {
        const val DEFAULT_DEDUP_WINDOW_MILLIS = 15 * 60 * 1000L

        /** Single in-memory store shared by the listener service and the UI. */
        val shared = MessageStore()
    }
}
