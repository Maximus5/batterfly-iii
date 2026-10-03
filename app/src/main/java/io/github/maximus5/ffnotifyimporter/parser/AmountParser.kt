package io.github.maximus5.ffnotifyimporter.parser

import java.math.BigDecimal

private val SEPARATORS = setOf(',', '.', '\'', ' ', ' ')

/**
 * Parses an amount, auto-detecting the decimal separator instead of requiring it configured per
 * rule: the *last* separator character (comma, period, apostrophe, space/NBSP) in [raw] is
 * treated as the decimal point only if exactly two digits follow it - real currency amounts are
 * written with 0 or 2 decimal digits, never anything else, so that's enough to tell a decimal
 * point from a thousands separator (`"12,50"` -> 12.50, but `"1,234"` -> 1234, not 1.234). Every
 * other separator is dropped as a thousands separator. Sign is ignored: direction comes from the
 * rule's kind, not the notification text.
 *
 * Known limitation: single-digit cents (`"12,5"` meaning 12.50) aren't detected as decimal -
 * real bank notifications always pad to two digits, so this hasn't come up in practice.
 */
fun parseAmount(raw: String): BigDecimal? {
    val lastSeparatorIndex = raw.indexOfLast { it in SEPARATORS }
    var decimalDigits: String? = null
    var decimalIndex = -1
    if (lastSeparatorIndex >= 0) {
        val afterSeparator = raw.substring(lastSeparatorIndex + 1).takeWhile { it in '0'..'9' }
        if (afterSeparator.length == 2) {
            decimalDigits = afterSeparator
            decimalIndex = lastSeparatorIndex
        }
    }

    val integerDigits = raw.substring(0, if (decimalIndex >= 0) decimalIndex else raw.length)
        .filter { it in '0'..'9' }

    if (integerDigits.isEmpty() && decimalDigits == null) return null
    val normalized = buildString {
        append(integerDigits.ifEmpty { "0" })
        if (decimalDigits != null) {
            append('.')
            append(decimalDigits)
        }
    }
    return normalized.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
}
