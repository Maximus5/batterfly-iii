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
 * **Second breaking change, same reasoning**: account side(s) hash by [TransactionDraft.sourceName]/
 * [TransactionDraft.destinationName] only now, never [TransactionDraft.sourceAccountId]/
 * [TransactionDraft.destinationAccountId] even when one is set. A real cross-app dedup miss the
 * user caught: two different rules for the same real Firefly account (a bank's own app's rule and
 * a wallet app's rule, say) don't necessarily agree on whether a Firefly account ID got cached for
 * it - one picked via `RuleEditFragment`'s account picker (had one), the other free-typed or saved
 * before the picker existed (didn't) - so hashing `id ?: name` produced two *different* values for
 * the *same* real account, and a genuine duplicate went uncaught. `ParseRule` no longer caches an
 * account ID at all as of this same fix (see its own KDoc) - `RuleEngine.buildDraft` always leaves
 * [TransactionDraft.sourceAccountId]/[TransactionDraft.destinationAccountId] null now, but this
 * function ignores them outright rather than relying on that alone, since `ui.
 * CreateTransactionFragment`'s own one-shot account picker can still set one (for posting
 * precision on that single transaction) - a hash that looked at it there would reintroduce the
 * exact same inconsistency against a rule-produced draft for the same account.
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
        RuleKind.WITHDRAWAL -> listOf(draft.sourceName.orEmpty())
        RuleKind.DEPOSIT -> listOf(draft.destinationName.orEmpty())
        RuleKind.TRANSFER -> listOf(draft.sourceName.orEmpty(), draft.destinationName.orEmpty())
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
    val foreignAmount: BigDecimal?,
    val foreignCurrencyCode: String?,
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
 * No id-vs-name wrinkle to handle here (there used to be one) - [transactionDedupHash] compares
 * account sides by name only now, so [candidate]'s own `sourceId`/`destinationId` never enter the
 * comparison at all; see that function's own KDoc for the real cross-rule dedup bug id-aware
 * hashing caused.
 *
 * A second wrinkle - a real false negative the user caught on a foreign-currency notification:
 * when [draft] has [TransactionDraft.foreignAmount]/[TransactionDraft.foreignCurrencyCode] set,
 * its own [TransactionDraft.amount]/[TransactionDraft.currencyCode] are only this app's *own*
 * approximate conversion (`rules.applyApproxConversion`), not a value Firefly's own stored amount
 * is likely to match exactly - comparing those would reject a real match essentially at random.
 * The foreign amount/currency is compared directly in that case instead (both sides' one precise,
 * bank-reported value), and the throwaway draft's amount/currency are set to [draft]'s own
 * (already-validated-identical-by-construction) values rather than [candidate]'s, so
 * [transactionDedupHash]'s own unconditional amount/currency contribution can't independently
 * re-reject what this already confirmed through the foreign-aware comparison. */
fun remoteCandidateMatches(draft: TransactionDraft, candidate: RemoteTransactionCandidate, includeDescription: Boolean, windowMinutes: Int): Boolean {
    val amount = draft.amount ?: return false
    val windowMillis = windowMinutes * 60_000L
    if (Math.abs(candidate.date.toEpochMilli() - draft.date.toEpochMilli()) > windowMillis) return false

    val draftForeignAmount = draft.foreignAmount
    val draftForeignCurrency = draft.foreignCurrencyCode
    if (draftForeignAmount != null && draftForeignCurrency != null) {
        if (candidate.foreignCurrencyCode != draftForeignCurrency) return false
        val candidateForeignAmount = candidate.foreignAmount ?: return false
        if (candidateForeignAmount.setScale(2, RoundingMode.HALF_UP) != draftForeignAmount.setScale(2, RoundingMode.HALF_UP)) return false
    } else {
        if (candidate.currencyCode != draft.currencyCode) return false
        if (candidate.amount.setScale(2, RoundingMode.HALF_UP) != amount.setScale(2, RoundingMode.HALF_UP)) return false
    }

    val candidateAsDraft = draft.copy(
        amount = amount,
        currencyCode = draft.currencyCode,
        sourceName = candidate.sourceName,
        destinationName = candidate.destinationName,
        description = candidate.description,
    )
    return transactionDedupHash(draft, includeDescription) == transactionDedupHash(candidateAsDraft, includeDescription)
}
