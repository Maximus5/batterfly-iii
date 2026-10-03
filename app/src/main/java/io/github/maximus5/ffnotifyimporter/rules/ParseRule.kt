package io.github.maximus5.ffnotifyimporter.rules

/**
 * One parsing template, scoped to a single Firefly III account. An account can have several
 * rules (different apps, different notification shapes, different kinds); an app can have
 * several rules too (one per account it can post to).
 *
 * [accountName] and [homeCurrency] are placeholders until a real Firefly III API connection
 * exists (see TODO.md) - free text today, real account/currency references later. Nothing in
 * `parser` or `RuleEngine` depends on that changing.
 */
data class ParseRule(
    val id: String,
    val label: String,
    val packageNames: Set<String> = emptySet(), // empty = any monitored app
    val kind: RuleKind,
    val accountName: String,
    val homeCurrency: String,
    val counterpartyAccountName: String? = null, // required for TRANSFER_OUT/TRANSFER_IN
    val pattern: String, // regex over title + "\n" + text, see Groups for the group conventions
    val decimalSeparator: Char = ',',
    val descriptionTemplate: String = "{title}",
    val notesTemplate: String = "{body}",
    val enabled: Boolean = true,
)
