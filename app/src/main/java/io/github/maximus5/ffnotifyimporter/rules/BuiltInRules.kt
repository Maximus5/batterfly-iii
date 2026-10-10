package io.github.maximus5.ffnotifyimporter.rules

/**
 * Two hardcoded [ParseRule] instances - not [RulesDb] rows, deliberately: that guarantees they
 * can't be edited/duplicated/deleted/reordered *structurally* (they're not in the editable table
 * at all), rather than relying on UI guards over an `isBuiltIn` DB column. The only persisted bit
 * is one shared enabled/disabled flag (`FireflyPrefs.isBuiltInRulesEnabled`), not a per-rule one -
 * these two always move together.
 *
 * Exists because a genuinely blank Rules list (or one where nothing matched) otherwise means the
 * user has to hand-author a regex themselves - the user's own judgment, "practically impossible
 * without Googling/AI" for a typical user. Design grounded in a real rules export the user
 * supplied (14 rules, 6+ apps), which confirmed two things directly rather than by guesswork:
 *
 * - [CURRENCY]/[AMOUNT] (the regex fragments below) are the user's own currency/amount capture,
 *   reused verbatim - already battle-tested across Amex/C24/Google Wallet/Miles & More/N26/
 *   Revolut, not invented fresh.
 * - Direction (withdrawal/deposit) keyword detection would not have worked: none of the 14 real
 *   rules use an English/German keyword a generic rule could plausibly guess from ("bei", "with
 *   <card name>" - not a direction word at all, "Zahlung erfolgreich", "Lastschrift eingelöst", or
 *   no keyword at all - Revolut's shape is pure line position). That killed an earlier 4-pattern
 *   "withdrawal rules + deposit rules by keyword" design in favor of this one: both rules default
 *   to [RuleKind.WITHDRAWAL] (the dominant real-world case), and a wrong guess is corrected by
 *   hand on the create screen instead (`ui.CreateTransactionFragment.showKindPicker`) - see
 *   TODO.md's own history for the full reasoning.
 *
 * Only the currency-position axis needs two separate rules at all - Java's `Pattern` forbids
 * reusing the same named group twice in one pattern even across alternation branches (unlike
 * .NET), so "currency before or after the amount" can't be expressed as one pattern; this follows
 * the same "one rule per real-world shape" convention every other format difference in this app
 * already uses, not a special case.
 *
 * `homeCurrency = ""` deliberately - a built-in rule isn't scoped to any one real account, so it
 * has no currency of its own to assume; it leans on [RuleEngine]'s `defaultAccountCurrency`
 * fallback instead (`FireflyPrefs.getDefaultAccountCurrency`). Likewise `sourceAccountName`/
 * `destinationAccountName` are blank - a built-in rule can't know which real Firefly account any
 * given app's money should land in - the create screen's own existing required-field validation
 * already makes the user fill that in by hand before they can submit. No `counterparty` capture
 * either, the user's own call: `descriptionTemplate = "{title}"`/`notesTemplate = "{body}"` just
 * use the notification's own raw text, the same shape several of the user's own real Google
 * Wallet rules already use for the identical reason (a generic pattern can't reliably isolate a
 * counterparty name the way a per-app rule can).
 */
object BuiltInRules {
    private const val CURRENCY = """[A-Z]{1,3}\$|[A-Z]{3}|[€£$¥]"""
    private const val AMOUNT = """[\d.,]+"""

    val CURRENCY_BEFORE_AMOUNT = ParseRule(
        id = "builtin-currency-before-amount",
        label = "Default: Currency before amount",
        kind = RuleKind.WITHDRAWAL,
        homeCurrency = "",
        pattern = """(?<currency>$CURRENCY)\s?(?<amount>$AMOUNT)""",
        descriptionTemplate = "{title}",
        notesTemplate = "{body}",
    )

    val CURRENCY_AFTER_AMOUNT = ParseRule(
        id = "builtin-currency-after-amount",
        label = "Default: Currency after amount",
        kind = RuleKind.WITHDRAWAL,
        homeCurrency = "",
        pattern = """(?<amount>$AMOUNT)\s?(?<currency>$CURRENCY)""",
        descriptionTemplate = "{title}",
        notesTemplate = "{body}",
    )

    val ALL: List<ParseRule> = listOf(CURRENCY_BEFORE_AMOUNT, CURRENCY_AFTER_AMOUNT)
    val IDS: Set<String> = ALL.map { it.id }.toSet()
}

/** Appends [BuiltInRules.ALL] after every rule already in [this] when [builtInRulesEnabled] -
 * [RuleEngine]'s own "first rule in list order that matches and yields a parsable amount wins"
 * ordering already gives "only used if nothing user-defined matched" for free, no engine change
 * needed. [builtInRulesEnabled] is a plain `Boolean`, not a `FireflyPrefs`/`Context` reference -
 * same "external config injected as data" shape [RuleEngine]'s own constructor params already
 * use, so every real match call site reads the flag itself and passes it in. */
fun List<ParseRule>.withBuiltIns(builtInRulesEnabled: Boolean): List<ParseRule> =
    if (builtInRulesEnabled) this + BuiltInRules.ALL else this
