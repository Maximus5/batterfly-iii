package io.github.maximus5.ffnotifyimporter.rules

import java.math.RoundingMode
import java.security.MessageDigest

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
