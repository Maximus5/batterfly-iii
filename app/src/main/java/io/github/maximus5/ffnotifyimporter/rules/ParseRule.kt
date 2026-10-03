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
 * [sourceAccountName]/[destinationAccountName] are macro templates, same mechanism and same
 * `{macro}` syntax as [descriptionTemplate]/[notesTemplate]/[categoryTemplate]/[tagsTemplate] -
 * not a separate "fixed vs. dynamic" concept. A plain account name with no `{macro}` in it is
 * just a fixed value (the common case for the side that's genuinely always the same account); if
 * the other side varies per notification (a merchant/payer name, say), write e.g.
 * `{counterparty}` referencing a named group declared in [pattern]. There is no automatic
 * fallback to any particular group name - this was tried (silently inherited from an earlier
 * draft, never actually asked for) and removed; what ends up in each field is exactly what's
 * written there, rendered.
 *
 * Exactly one of [sourceAccountName]/[destinationAccountName] is expected to render to something
 * for WITHDRAWAL/DEPOSIT; TRANSFER needs both - validated by [validateRule].
 */
data class ParseRule(
    val id: String,
    val label: String,
    val packageNames: Set<String> = emptySet(),
    val kind: RuleKind,
    val sourceAccountName: String = "",
    val destinationAccountName: String = "",
    val homeCurrency: String,
    val pattern: String, // regex over title + "\n" + text, see Groups for the group conventions
    val descriptionTemplate: String = "",
    val notesTemplate: String = "",
    val categoryTemplate: String = "", // renders to one category name; blank = none
    val tagsTemplate: String = "", // renders then splits on ',' (trimmed, blanks dropped)
    val enabled: Boolean = true,
)
