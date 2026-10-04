package io.github.maximus5.ffnotifyimporter.data

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

    /**
     * Invoked (outside the lock) after a genuinely new message is recorded by [add] - used to
     * mirror it to on-disk storage when the user has opted into message retention. Null by
     * default, matching the original in-memory-only design: nothing persisted unless something
     * explicitly wires this up.
     */
    var onNewMessage: ((CapturedMessage) -> Unit)? = null

    /** Mirror of [onNewMessage] for [remove] - lets persistence delete the on-disk row too. */
    var onMessageRemoved: ((CapturedMessage) -> Unit)? = null

    /** Mirror of [onNewMessage] for [setTransactionId] - lets persistence update the on-disk
     * row's `firefly_transaction_id` too. */
    var onMessageUpdated: ((CapturedMessage) -> Unit)? = null

    /** Returns the recorded [CapturedMessage] (with its exact, assigned [CapturedMessage.
     * timestampMillis]), or null if it was dropped as a duplicate. Returning the message itself,
     * not just a success [Boolean], matters once a caller needs to act on *this exact* message
     * afterward (`posting.AutoCreateScheduler`, enqueued right after `add` - it needs the same
     * [CapturedMessage.timestampMillis] that was actually stored, computed once here inside the
     * lock, since re-deriving "now" a second time at the call site could race against a second
     * notification arriving in between and land on a different value, breaking
     * [setTransactionId]'s later exact-identity lookup).
     * [timestampMillis] defaults to "now" (every real notification-capture call site relies on
     * this default) - `ui.AddTestMessageFragment` is the one caller that overrides it, so a test
     * message can carry a chosen date/time instead of always being "just now", useful for
     * reproducing a rule bug tied to a specific real notification's timestamp. */
    fun add(packageName: String, appLabel: String, title: String, text: String, timestampMillis: Long = clock()): CapturedMessage? {
        val now = timestampMillis
        val recorded: CapturedMessage
        val snapshot: List<CapturedMessage>
        synchronized(lock) {
            val isDuplicate = messages.any { existing ->
                existing.packageName == packageName &&
                    existing.title == title &&
                    existing.text == text &&
                    Math.abs(now - existing.timestampMillis) <= dedupWindowMillis
            }
            if (isDuplicate) return null
            recorded = CapturedMessage(packageName, appLabel, title, text, now)
            messages.add(0, recorded)
            snapshot = messages.toList()
        }
        listeners.forEach { it(snapshot) }
        onNewMessage?.invoke(recorded)
        return recorded
    }

    /**
     * Bulk-seeds already-deduplicated entries (e.g. loaded from on-disk storage at startup)
     * without re-running the live dedup check in [add] - they were deduplicated when first
     * recorded. [historical] must be oldest-first; afterwards the newest one is at the front of
     * the list, same ordering [add] produces. Does not touch [onNewMessage] - restoring is not
     * the same as capturing something new.
     */
    fun restore(historical: List<CapturedMessage>) {
        if (historical.isEmpty()) return
        val snapshot: List<CapturedMessage>
        synchronized(lock) {
            historical.forEach { messages.add(0, it) }
            snapshot = messages.toList()
        }
        listeners.forEach { it(snapshot) }
    }

    /** Drops everything currently held - for restoring a full backup (replace, not merge), not
     * used by the normal one-at-a-time multi-select delete flow ([remove]). Does not touch
     * [onMessageRemoved] - the caller is expected to clear on-disk storage itself if needed,
     * same "restore doesn't trigger the normal capture-time hooks" reasoning as [restore]. */
    fun clear() {
        val snapshot: List<CapturedMessage>
        synchronized(lock) {
            messages.clear()
            snapshot = messages.toList()
        }
        listeners.forEach { it(snapshot) }
    }

    /** Removes [message] (matched by value equality) from history. Returns true if it was there. */
    fun remove(message: CapturedMessage): Boolean {
        val snapshot: List<CapturedMessage>
        synchronized(lock) {
            if (!messages.remove(message)) return false
            snapshot = messages.toList()
        }
        listeners.forEach { it(snapshot) }
        onMessageRemoved?.invoke(message)
        return true
    }

    /**
     * Replaces the entry matching [message] - identified by every field *except*
     * [CapturedMessage.fireflyTransactionId], since that's exactly the field being changed - with
     * a copy carrying [transactionId], preserving its position in the list. Returns the updated
     * message (so the caller doesn't have to re-derive it), or null if [message] isn't in the
     * store anymore (e.g. deleted between the dialog opening and the create call finishing).
     */
    fun setTransactionId(message: CapturedMessage, transactionId: String?): CapturedMessage? {
        val updated: CapturedMessage
        val snapshot: List<CapturedMessage>
        synchronized(lock) {
            val index = messages.indexOfFirst {
                it.packageName == message.packageName &&
                    it.title == message.title &&
                    it.text == message.text &&
                    it.timestampMillis == message.timestampMillis
            }
            if (index < 0) return null
            updated = messages[index].copy(fireflyTransactionId = transactionId)
            messages[index] = updated
            snapshot = messages.toList()
        }
        listeners.forEach { it(snapshot) }
        onMessageUpdated?.invoke(updated)
        return updated
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
