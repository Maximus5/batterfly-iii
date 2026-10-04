package io.github.maximus5.ffnotifyimporter.rules

import java.math.BigDecimal
import java.time.Instant

/** A problem with a [TransactionDraft] worth surfacing before anything gets posted anywhere. */
enum class DraftIssue {
    /** Only a foreign-currency amount was found; the home-currency amount needs manual entry. */
    LOCAL_AMOUNT_MISSING,

    /** [ParseRule.dateFormat] is set and [Groups.DATE] was captured, but the captured text didn't
     * parse against that format (typo in the format, or the text just doesn't match) - [date]
     * falls back to the notification's own arrival time rather than blocking the match, but this
     * flags that the fallback happened instead of the date silently being wrong with no warning. */
    DATE_UNPARSABLE,
}

/** What a [ParseRule] detected in a notification - "what we matched", now postable via
 * `network.FireflyApiClient.createTransaction` (see `ui.MessagesFragment`'s "Создать" button). */
data class TransactionDraft(
    val kind: RuleKind,
    val date: Instant,
    val amount: BigDecimal?, // in homeCurrency; null if only a foreign amount was found
    val currencyCode: String, // the rule's homeCurrency
    val foreignAmount: BigDecimal?,
    val foreignCurrencyCode: String?,
    val sourceName: String?,
    // Always null from RuleEngine.buildDraft - ParseRule no longer caches a Firefly account id at
    // all (see its own KDoc for the real cross-rule dedup bug that caused). Only ever set by
    // ui.CreateTransactionFragment's own one-shot account picker, for posting precision on that
    // one transaction being created right now - preferred over sourceName when posting
    // (FireflyJson.buildTransactionRequestBody), but never fed into transactionDedupHash, which
    // compares by name only regardless of whether either side happens to carry one.
    val sourceAccountId: String?,
    val destinationName: String?,
    val destinationAccountId: String?, // same as sourceAccountId above, mirrored for the other side
    val description: String,
    val notes: String,
    val category: String?,
    val tags: List<String>,
    val issues: Set<DraftIssue>,
)
