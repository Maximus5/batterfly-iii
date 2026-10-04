package io.github.maximus5.ffnotifyimporter.rules

import java.math.BigDecimal
import java.time.Instant

/** A problem with a [TransactionDraft] worth surfacing before anything gets posted anywhere. */
enum class DraftIssue {
    /** Only a foreign-currency amount was found; the home-currency amount needs manual entry. */
    LOCAL_AMOUNT_MISSING,
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
    val sourceAccountId: String?, // from ParseRule.sourceAccountId - preferred over sourceName when posting
    val destinationName: String?,
    val destinationAccountId: String?, // from ParseRule.destinationAccountId
    val description: String,
    val notes: String,
    val category: String?,
    val tags: List<String>,
    val issues: Set<DraftIssue>,
)
