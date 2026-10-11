package io.github.maximus5.ffnotifyimporter.posting

import io.github.maximus5.ffnotifyimporter.rules.TransactionDraft
import io.github.maximus5.ffnotifyimporter.rules.transactionDedupHash

/**
 * What a review notification ([ReviewNotifications]) remembers about the draft it was posted for,
 * so a second notification for the same real payment (typically the bank app plus a wallet app)
 * isn't added to the shade next to it. Both hash variants are kept, like `CreatedTransactionsDb`
 * does, so flipping "also compare description" between two notifications doesn't make them stop
 * matching. [dateMillis] bounds the match to the same dedup window the transaction dedup uses -
 * the hash alone (amount, account, description) would also swallow a genuinely separate identical
 * payment hours later while the first notification still sits unswiped.
 */
data class ShadeEntry(val hashWithDescription: String, val hashWithoutDescription: String, val dateMillis: Long)

fun shadeEntryFor(draft: TransactionDraft): ShadeEntry? {
    if (draft.amount == null) return null
    return ShadeEntry(
        hashWithDescription = transactionDedupHash(draft, includeDescription = true),
        hashWithoutDescription = transactionDedupHash(draft, includeDescription = false),
        dateMillis = draft.date.toEpochMilli(),
    )
}

fun isDuplicateInShade(active: List<ShadeEntry>, candidate: ShadeEntry, includeDescription: Boolean, windowMinutes: Int): Boolean {
    val windowMillis = windowMinutes * 60_000L
    return active.any { existing ->
        val sameHash = if (includeDescription) {
            existing.hashWithDescription == candidate.hashWithDescription
        } else {
            existing.hashWithoutDescription == candidate.hashWithoutDescription
        }
        sameHash && Math.abs(existing.dateMillis - candidate.dateMillis) <= windowMillis
    }
}
