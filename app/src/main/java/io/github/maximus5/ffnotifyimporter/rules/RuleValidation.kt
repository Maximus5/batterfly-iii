package io.github.maximus5.ffnotifyimporter.rules

import io.github.maximus5.ffnotifyimporter.parser.namedGroupNames

/** Human-readable validation errors to show in the rule editor before letting a save through. */
fun validateRule(rule: ParseRule): List<String> {
    val errors = mutableListOf<String>()
    if (rule.label.isBlank()) errors += "Label can't be empty"
    if (rule.accountName.isBlank()) errors += "Account can't be empty"
    if (rule.homeCurrency.isBlank()) errors += "Currency can't be empty"

    runCatching { Regex(rule.pattern) }.onFailure { return errors + "Invalid regex: ${it.message}" }
    val declaredGroups = namedGroupNames(rule.pattern)

    if (Groups.AMOUNT !in declaredGroups) {
        errors += "Pattern must define group '${Groups.AMOUNT}'"
    }

    val isTransfer = rule.kind == RuleKind.TRANSFER_OUT || rule.kind == RuleKind.TRANSFER_IN
    if (isTransfer && rule.counterpartyAccountName.isNullOrBlank()) {
        errors += "Transfer rules need a counterparty account"
    }

    val validMacros = declaredGroups.toSet() + setOf("title", "body")
    val usedMacros = templateMacroNames(rule.descriptionTemplate) + templateMacroNames(rule.notesTemplate)
    val unknownMacros = usedMacros - validMacros
    if (unknownMacros.isNotEmpty()) {
        errors += "Unknown macro(s) in description/notes: ${unknownMacros.sorted().joinToString()}"
    }

    return errors
}
