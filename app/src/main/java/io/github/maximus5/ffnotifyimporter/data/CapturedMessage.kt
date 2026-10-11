package io.github.maximus5.ffnotifyimporter.data

/** [fireflyTransactionId] - set once this message has had a transaction created from it
 * (`ui.MessagesFragment`'s "Create Transaction"/dedup "Already Created" flow), via
 * `MessageStore.setTransactionId` - deliberately *not* part of what identifies a message
 * elsewhere (dedup, removal, `MessageHistoryDb` lookups all key on the other five fields), since
 * it's exactly the field those call sites want to update in place. */
data class CapturedMessage(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val timestampMillis: Long,
    val fireflyTransactionId: String? = null,
    /** Label of the rule that was applied when [fireflyTransactionId] was created from this message
     * (null if linked another way, or not linked). Lives and dies with [fireflyTransactionId]: set
     * and cleared together by `MessageStore.setTransactionId`, and ignored for identity everywhere
     * that already ignores that field. A label, not a rule id: it must still read right after the
     * rule is renamed or deleted, and shows the rule as it was named when it fired. */
    val appliedRuleLabel: String? = null,
)
