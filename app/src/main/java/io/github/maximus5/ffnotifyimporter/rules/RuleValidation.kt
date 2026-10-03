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

    val validMacros = declaredGroups.toSet() + FIXED_MACROS
    val usedMacros = templateMacroNames(rule.sourceAccountName) +
        templateMacroNames(rule.destinationAccountName) +
        templateMacroNames(rule.descriptionTemplate) +
        templateMacroNames(rule.notesTemplate) +
        templateMacroNames(rule.categoryTemplate) +
        templateMacroNames(rule.tagsTemplate)
    val unknownMacros = usedMacros - validMacros
    if (unknownMacros.isNotEmpty()) {
        errors += "Unknown macro(s): ${unknownMacros.sorted().joinToString()}"
    }

    return errors
}
