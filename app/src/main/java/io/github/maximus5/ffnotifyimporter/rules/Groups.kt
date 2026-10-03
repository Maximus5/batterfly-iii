package io.github.maximus5.ffnotifyimporter.rules

/**
 * Named-group conventions a [ParseRule.pattern] can define, that this layer knows how to use.
 * `amount` is the only mandatory one. The `parser` layer doesn't know any of these names - it
 * just extracts whatever's declared.
 *
 * `amount`/`currency`               - amount as reported by the bank
 * `foreignAmount`/`foreignCurrency` - original amount for a cross-currency payment
 *
 * Nothing else is privileged - e.g. a `counterparty` group isn't special to `RuleEngine`, it's
 * just another name available to `{macro}` templates (description/notes/category/tags) via
 * [renderTemplate]. `source`/`destination` are always [ParseRule.sourceAccountName]/
 * [ParseRule.destinationAccountName] as configured - never auto-filled from a regex capture.
 */
object Groups {
    const val AMOUNT = "amount"
    const val CURRENCY = "currency"
    const val FOREIGN_AMOUNT = "foreignAmount"
    const val FOREIGN_CURRENCY = "foreignCurrency"
}
