package io.github.maximus5.ffnotifyimporter.rules

/**
 * One parsing template. A Firefly III account can have several rules (different apps, different
 * notification shapes, different kinds); an app can have several rules too (one per account it
 * can post to).
 *
 * [sourceAccountName]/[destinationAccountName]/[homeCurrency] are placeholders until a real
 * Firefly III API connection exists (see TODO.md) - free text today, real account/currency
 * references later. Nothing in `parser` or `RuleEngine` depends on that changing.
 *
 * Exactly one of [sourceAccountName]/[destinationAccountName] is expected to be left blank for
 * WITHDRAWAL/DEPOSIT - [RuleEngine] fills it in from the pattern's `counterparty` group if
 * present (the merchant/payer varies per notification, so it's rarely a fixed value). TRANSFER
 * needs both - validated by [validateRule].
 */
data class ParseRule(
    val id: String,
    val label: String,
    val packageNames: Set<String> = emptySet(),
    val kind: RuleKind,
    val sourceAccountName: String? = null,
    val destinationAccountName: String? = null,
    val homeCurrency: String,
    val pattern: String, // regex over title + "\n" + text, see Groups for the group conventions
    val descriptionTemplate: String = "",
    val notesTemplate: String = "",
    val categoryTemplate: String = "", // renders to one category name; blank = none
    val tagsTemplate: String = "", // renders then splits on ',' (trimmed, blanks dropped)
    val enabled: Boolean = true,
)
