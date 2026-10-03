package io.github.maximus5.ffnotifyimporter.ui

import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.rules.RuleKind

/** Localized, sentence-case label for a [RuleKind] - the enum name itself is ALL_CAPS and raw. */
fun RuleKind.labelRes(): Int = when (this) {
    RuleKind.WITHDRAWAL -> R.string.rule_kind_withdrawal
    RuleKind.DEPOSIT -> R.string.rule_kind_deposit
    RuleKind.TRANSFER -> R.string.rule_kind_transfer
}
