package io.github.maximus5.ffnotifyimporter.rules

import io.github.maximus5.ffnotifyimporter.parser.namedGroupNames

/** Human-readable validation errors to show in the rule editor before letting a save through. */
fun validateRule(rule: ParseRule): List<String> {
    val errors = mutableListOf<String>()
    if (rule.label.isBlank()) errors += "Label can't be empty"
    if (rule.packageNames.isEmpty()) errors += "Pick an app"
    if (rule.homeCurrency.isBlank()) errors += "Currency can't be empty"

    when (rule.kind) {
        RuleKind.WITHDRAWAL ->
            if (rule.sourceAccountName.isBlank()) errors += "Withdrawal rules need a source account"
        RuleKind.DEPOSIT ->
            if (rule.destinationAccountName.isBlank()) errors += "Deposit rules need a destination account"
        RuleKind.TRANSFER -> {
            if (rule.sourceAccountName.isBlank()) errors += "Transfer rules need a source account"
            if (rule.destinationAccountName.isBlank()) errors += "Transfer rules need a destination account"
        }
    }

    // Same RegexOption as extractGroups uses at match time - compiled identically here so a
    // pattern that's only invalid under one of the two never slips through as "valid".
    runCatching { Regex(rule.pattern, RegexOption.DOT_MATCHES_ALL) }
        .onFailure { return errors + "Invalid regular expression: ${it.message}" }
    val declaredGroups = namedGroupNames(rule.pattern)

    if (Groups.AMOUNT !in declaredGroups) {
        errors += "Pattern must define group '${Groups.AMOUNT}'"
    }
    // Deliberately nothing else about the pattern's groups is required - currency/counterparty/
    // foreignAmount/foreignCurrency are all optional, same as the fields that would reference
    // them. A template macro that doesn't match any declared group used to be a save-blocking
    // error here too ("Unknown macro(s): ..."), but that doubled up with renderTemplate's own
    // graceful handling (an unknown macro just renders as literal text, see MacroTemplate.kt) and
    // blocked saving a rule over something harmless, e.g. editing a pattern down to just `amount`
    // while a template field still mentions a macro from an earlier draft of it.

    return errors
}
