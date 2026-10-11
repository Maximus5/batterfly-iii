package io.github.maximus5.ffnotifyimporter.posting

import io.github.maximus5.ffnotifyimporter.rules.TransactionDraft
import io.github.maximus5.ffnotifyimporter.rules.accountApproximate
import io.github.maximus5.ffnotifyimporter.rules.transactionDedupHash
import io.github.maximus5.ffnotifyimporter.rules.transactionDedupHashIgnoringAccounts

/**
 * What a review notification ([ReviewNotifications]) remembers about the draft it was posted for,
 * so a second notification for the same real payment (typically the bank app plus a wallet app)
 * isn't added to the shade next to it. Both hash variants are kept, like `CreatedTransactionsDb`
 * does, so flipping "also compare description" between two notifications doesn't make them stop
 * matching. [dateMillis] bounds the match to the same dedup window the transaction dedup uses -
 * the hash alone (amount, account, description) would also swallow a genuinely separate identical
 * payment hours later while the first notification still sits unswiped.
 *
 * [hashWithoutAccounts]/[approximate] are for a draft from an `accountApproximate` rule (a bank
 * notification that does not say which account was charged): such an entry matches by the
 * account-agnostic hash. See [isDuplicateInShade] and [supersededApproximateIndex] for who wins.
 */
data class ShadeEntry(
    val hashWithDescription: String,
    val hashWithoutDescription: String,
    val dateMillis: Long,
    val hashWithoutAccounts: String = "",
    val approximate: Boolean = false,
)

fun shadeEntryFor(draft: TransactionDraft): ShadeEntry? {
    if (draft.amount == null) return null
    return ShadeEntry(
        hashWithDescription = transactionDedupHash(draft, includeDescription = true),
        hashWithoutDescription = transactionDedupHash(draft, includeDescription = false),
        dateMillis = draft.date.toEpochMilli(),
        hashWithoutAccounts = transactionDedupHashIgnoringAccounts(draft),
        approximate = draft.accountApproximate,
    )
}

/** True if [candidate] should not get its own notification because [active] already holds one for
 * the same payment. Two precise entries compare by the usual account-aware hash. An approximate
 * [candidate] matches any entry (precise or approximate) by the account-agnostic hash - a precise
 * one is the better source, and an approximate one is the same bank message again. A precise
 * [candidate] is never suppressed by an approximate entry: it wins instead, see
 * [supersededApproximateIndex]. */
fun isDuplicateInShade(active: List<ShadeEntry>, candidate: ShadeEntry, includeDescription: Boolean, windowMinutes: Int): Boolean {
    val windowMillis = windowMinutes * 60_000L
    return active.any { existing ->
        val sameHash = when {
            candidate.approximate -> existing.hashWithoutAccounts.isNotEmpty() && existing.hashWithoutAccounts == candidate.hashWithoutAccounts
            existing.approximate -> false
            includeDescription -> existing.hashWithDescription == candidate.hashWithDescription
            else -> existing.hashWithoutDescription == candidate.hashWithoutDescription
        }
        sameHash && Math.abs(existing.dateMillis - candidate.dateMillis) <= windowMillis
    }
}

/** For a *precise* [candidate] (a transaction was just created from it, or its own notification is
 * about to be posted): the index in [active] of the one approximate entry for the same payment that
 * it makes redundant - the closest in time, and only one, so two real same-amount payments reported
 * by an approximate source are not both swept away by a single precise one. Null if there is none,
 * or if [candidate] is itself approximate. */
fun supersededApproximateIndex(active: List<ShadeEntry>, candidate: ShadeEntry, windowMinutes: Int): Int? {
    if (candidate.approximate) return null
    val windowMillis = windowMinutes * 60_000L
    return active.withIndex()
        .filter { (_, existing) ->
            existing.approximate &&
                existing.hashWithoutAccounts.isNotEmpty() &&
                existing.hashWithoutAccounts == candidate.hashWithoutAccounts &&
                Math.abs(existing.dateMillis - candidate.dateMillis) <= windowMillis
        }
        .minByOrNull { (_, existing) -> Math.abs(existing.dateMillis - candidate.dateMillis) }
        ?.index
}
