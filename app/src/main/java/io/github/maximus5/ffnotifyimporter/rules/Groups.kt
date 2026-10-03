package io.github.maximus5.ffnotifyimporter.rules

/**
 * Named-group conventions a [ParseRule.pattern] can define, that this layer knows how to use.
 * `amount` is the only mandatory one. The `parser` layer doesn't know any of these names - it
 * just extracts whatever's declared.
 *
 * `amount`/`currency`           - amount as reported by the bank
 * `foreignAmount`/`foreignCurrency` - original amount for a cross-currency payment
 * `counterparty`                - payee/payer name
 */
object Groups {
    const val AMOUNT = "amount"
    const val CURRENCY = "currency"
    const val FOREIGN_AMOUNT = "foreignAmount"
    const val FOREIGN_CURRENCY = "foreignCurrency"
    const val COUNTERPARTY = "counterparty"
}
