package io.github.maximus5.ffnotifyimporter.ui

import java.math.BigDecimal

/** Firefly's `amount`/`foreign_amount` JSON fields come back at whatever scale its
 * `DECIMAL(32,12)` column stores, e.g. "61.160000000000" - correct, but unreadable on screen.
 * Strips trailing zeros, then pads back up to 2 decimal places if stripping went below that
 * (cents, what most currencies use) - "61.160000000000" -> "61.16", "100.000000000000" ->
 * "100.00", a genuinely 3-decimal amount stays 3-decimal rather than getting rounded down. */
internal fun formatAmountForDisplay(amount: BigDecimal): String {
    val stripped = amount.stripTrailingZeros()
    return (if (stripped.scale() < 2) stripped.setScale(2) else stripped).toPlainString()
}
