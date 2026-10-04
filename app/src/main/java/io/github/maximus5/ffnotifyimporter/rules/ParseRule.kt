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
 *
 * [sourceAccountId]/[destinationAccountId] are optional - set only when the corresponding name
 * was picked from `FireflyApiClient.listAssetAccounts()` (the rule editor's asset-account
 * picker, see ARCHITECTURE.md), null for a rule whose account fields are still free-typed/macro
 * text (the counterparty side always, or any rule saved before this existed). A Firefly account
 * ID survives a rename in Firefly; the name alone doesn't - posting should prefer the ID when
 * it's present and still matches the displayed name, and fall back to posting by name otherwise,
 * exactly the same `source_name`/`destination_name` behavior this app already had.
 *
 * [dateFormat] is blank by default, meaning "use the notification's own arrival time as the
 * transaction date" (the original, still-default behavior). Non-blank - a `java.time.format.
 * DateTimeFormatter` pattern, e.g. `dd.MM.yy` - opts into using whatever [Groups.DATE] captures
 * instead, for banks that announce a *future* charge date separately from when the notification
 * itself arrives (e.g. "the charge will happen on 01.02.30"). Deliberately user-specified, not
 * auto-detected: unlike an amount's decimal separator, a date like "01.02.30" is genuinely
 * ambiguous (day-first or month-first) without knowing the bank's own convention - guessing would
 * risk silently posting the wrong date, see TODO.md.
 */
data class ParseRule(
    val id: String,
    val label: String,
    val packageNames: Set<String> = emptySet(),
    val kind: RuleKind,
    val sourceAccountName: String = "",
    val destinationAccountName: String = "",
    val sourceAccountId: String? = null,
    val destinationAccountId: String? = null,
    val homeCurrency: String,
    val pattern: String, // regex over title + "\n" + text, see Groups for the group conventions
    val descriptionTemplate: String = "",
    val notesTemplate: String = "",
    val categoryTemplate: String = "", // renders to one category name; blank = none
    val tagsTemplate: String = "", // renders then splits on ',' (trimmed, blanks dropped)
    val enabled: Boolean = true,
    val dateFormat: String = "",
)
