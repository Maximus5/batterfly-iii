package io.github.maximus5.ffnotifyimporter.rules

/** A code<->symbol mapping the user maintains locally - a placeholder for real Firefly III
 * currency data (see TODO.md), same idea as [ParseRule]'s account name placeholders. */
data class CurrencyEntry(val code: String, val symbol: String)
