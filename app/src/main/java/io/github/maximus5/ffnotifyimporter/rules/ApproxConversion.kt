package io.github.maximus5.ffnotifyimporter.rules

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Fills in [TransactionDraft.amount] from a fetched exchange rate, for a draft currently flagged
 * [DraftIssue.LOCAL_AMOUNT_MISSING] - pure, no network dependency (the caller fetches [rate]
 * itself, see `network.ExchangeRateClient`; `rules` doesn't depend on `network`, same layering as
 * `network` not depending on `rules`), so this half of the flow stays plain-Kotlin unit testable
 * same as [RuleEngine]. [rate] is "how many units of the draft's home currency one unit of the
 * foreign currency is worth" (`base` = foreign, `quote` = home, in `ExchangeRateClient` terms).
 * [approxTag], when non-blank, is appended to the draft's existing tags (skipped if already
 * present) - the user-configurable marker (`FireflyPrefs.getApproxConversionTag`) that this
 * amount is computed, not read straight off the notification.
 */
fun applyApproxConversion(draft: TransactionDraft, rate: BigDecimal, approxTag: String): TransactionDraft {
    val foreignAmount = draft.foreignAmount ?: return draft
    val amount = foreignAmount.multiply(rate).setScale(2, RoundingMode.HALF_UP)
    val tags = if (approxTag.isBlank() || draft.tags.contains(approxTag)) {
        draft.tags
    } else {
        draft.tags + approxTag
    }
    return draft.copy(
        amount = amount,
        tags = tags,
        issues = draft.issues - DraftIssue.LOCAL_AMOUNT_MISSING,
    )
}
