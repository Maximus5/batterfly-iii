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
    val parts = listOf(
        draft.sourceAccountId ?: draft.sourceName.orEmpty(),
        draft.destinationAccountId ?: draft.destinationName.orEmpty(),
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
 * history) - looks like the same real-world payment as [draft]. Reuses [transactionDedupHash]
 * rather than re-deriving the same amount/currency/description/account comparison a second way:
 * [candidate] is reshaped into a throwaway [TransactionDraft] first, then the two hashes are
 * compared directly.
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
