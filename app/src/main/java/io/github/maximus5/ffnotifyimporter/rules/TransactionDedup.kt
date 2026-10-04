package io.github.maximus5.ffnotifyimporter.rules

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant

/**
 * The dedup key for a [TransactionDraft] about to be posted - same real-world payment, reported
 * by two different monitored apps (a bank's own app and Google/Apple Wallet both firing for one
 * card tap, say), should hash the same so `CreatedTransactionsDb` can catch it even though the
 * two captured messages themselves are completely different (see TODO.md's dedup history). Pure,
 * no DB/network dependency, so it's plain-Kotlin unit testable on its own.
 *
 * Only the account side(s) that actually define identity for [TransactionDraft.kind] go into the
 * hash - source for `WITHDRAWAL`, destination for `DEPOSIT`, both for `TRANSFER` - the same sides
 * `RuleValidation.validateRule` requires a name for, and the same reasoning: the other side isn't
 * part of what makes this transaction *this* transaction. A `WITHDRAWAL` rule is never required to
 * name a destination, so including it anyway meant the hash leaned on a field this app frequently
 * leaves unset - harmless for comparing two local hashes against each other (always equally
 * blank), but a real bug once anything *else* (Firefly's own stored data, see
 * `remoteCandidateMatches`) entered the comparison with a real value sitting there instead,
 * caught directly by the user.
 *
 * **Breaking change, accepted deliberately, not fixed**: this changes what hash the same draft
 * produces whenever the kind-irrelevant side was non-blank before (an uncommon but real case -
 * e.g. a `WITHDRAWAL` rule whose destination macro template still rendered something). A
 * `CreatedTransactionsDb` row written under the old formula for one of those won't be found by a
 * lookup computed under this one. The user's own explicit call on it ("не мудри, меняй
 * transactionDedupHash") rather than threading a kind-awareness workaround through every caller
 * instead - same "don't guess, ask/do the obvious thing" instinct as elsewhere in this app, just
 * applied to *not* over-engineering around a real fix this time.
 *
 * [includeDescription] - on by default (`FireflyPrefs.isDedupIncludeDescription`) - trades a
 * slightly higher false-negative rate (an occasional real duplicate charge from the same merchant
 * within the window goes uncaught) for zero risk of a false positive silently dropping a
 * genuinely different transaction; see TODO.md for why that tradeoff was picked deliberately.
 *
 * [TransactionDraft.amount] must be resolved (non-null) before this is called - the caller
 * (`ui.MessagesFragment`) only offers "Создать" once it is.
 */
fun transactionDedupHash(draft: TransactionDraft, includeDescription: Boolean): String {
    val amount = requireNotNull(draft.amount) { "transactionDedupHash needs a resolved amount" }
    val accountParts = when (draft.kind) {
        RuleKind.WITHDRAWAL -> listOf(draft.sourceAccountId ?: draft.sourceName.orEmpty())
        RuleKind.DEPOSIT -> listOf(draft.destinationAccountId ?: draft.destinationName.orEmpty())
        RuleKind.TRANSFER -> listOf(
            draft.sourceAccountId ?: draft.sourceName.orEmpty(),
            draft.destinationAccountId ?: draft.destinationName.orEmpty(),
        )
    }
    val parts = accountParts + listOf(
        amount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
        draft.currencyCode,
        if (includeDescription) draft.description else "",
    )
    val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

/** One Firefly transaction split as returned by `network.FireflyApiClient.
 * searchNearbyTransactions`, stripped down to plain values so this file (like the rest of
 * `rules`) stays free of a `network` dependency - the caller (`ui.MessagesFragment`) maps
 * `network.FireflyTransactionCandidate` into this before calling [remoteCandidateMatches]. */
data class RemoteTransactionCandidate(
    val groupId: String,
    val date: Instant,
    val amount: BigDecimal,
    val currencyCode: String,
    val description: String,
    val sourceId: String?,
    val sourceName: String?,
    val destinationId: String?,
    val destinationName: String?,
)

/**
 * True if [candidate] - a transaction Firefly already has, found by `ui.MessagesFragment`'s
 * remote duplicate check once the local dedup log already came up empty (see TODO.md's dedup
 * history) - looks like the same real-world payment as [draft]. Needed because [candidate] isn't
 * a [TransactionDraft] (different shape - no notes/category/tags/foreignAmount, but a `groupId`)
 * and because [transactionDedupHash] deliberately doesn't cover date at all (the window check
 * below is its own thing, same reason `CreatedTransactionsDb.findWithinWindow` does its own SQL
 * `BETWEEN` rather than folding date into the hash). Reuses [transactionDedupHash] for the rest
 * rather than re-deriving the same amount/currency/description/account comparison a second way:
 * [candidate] is reshaped into a throwaway [TransactionDraft] first, then the two hashes are
 * compared directly - this is also why [transactionDedupHash] being kind-aware (only the side(s)
 * that matter for [TransactionDraft.kind]) is enough on its own to fix a real miss the user
 * caught, with nothing extra needed here: whichever side doesn't matter for this kind never
 * enters the comparison, however it's filled in on either side.
 *
 * One wrinkle [candidate] has that a locally-recorded row never does: Firefly assigns a real
 * account id to *every* account, including a counterparty this app only ever captured by name
 * (the expense/revenue side of a withdrawal/deposit - this app's own rule editor only picks
 * *asset*-account ids, see `network.FireflyAccount`'s KDoc). Comparing [candidate]'s id against
 * [draft]'s lack of one for that side would never match even for the exact same counterparty, so
 * the throwaway draft only keeps [candidate]'s id for a side where [draft] *also* has one -
 * otherwise it's compared by name instead, same as [draft] itself is.
 */
fun remoteCandidateMatches(draft: TransactionDraft, candidate: RemoteTransactionCandidate, includeDescription: Boolean, windowMinutes: Int): Boolean {
    val amount = draft.amount ?: return false
    val windowMillis = windowMinutes * 60_000L
    if (Math.abs(candidate.date.toEpochMilli() - draft.date.toEpochMilli()) > windowMillis) return false
    if (candidate.currencyCode != draft.currencyCode) return false
    if (candidate.amount.setScale(2, RoundingMode.HALF_UP) != amount.setScale(2, RoundingMode.HALF_UP)) return false

    val candidateAsDraft = draft.copy(
        amount = candidate.amount,
        currencyCode = candidate.currencyCode,
        sourceAccountId = if (draft.sourceAccountId != null) candidate.sourceId else null,
        sourceName = candidate.sourceName,
        destinationAccountId = if (draft.destinationAccountId != null) candidate.destinationId else null,
        destinationName = candidate.destinationName,
        description = candidate.description,
    )
    return transactionDedupHash(draft, includeDescription) == transactionDedupHash(candidateAsDraft, includeDescription)
}
