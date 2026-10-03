package io.github.maximus5.ffnotifyimporter.parser

import java.math.BigDecimal

/**
 * Parses an amount with an explicitly configured decimal separator - no guessing. Every other
 * character ('.', ',', spaces, NBSP, apostrophe, currency symbols, signs...) is dropped as a
 * thousands separator or noise. Sign is ignored: direction comes from the rule's kind, not the
 * notification text.
 */
fun parseAmount(raw: String, decimalSeparator: Char): BigDecimal? {
    val sb = StringBuilder()
    for (c in raw) {
        when {
            c in '0'..'9' -> sb.append(c)
            c == decimalSeparator -> sb.append('.')
        }
    }
    if (sb.count { it == '.' } > 1) return null
    return sb.toString().toBigDecimalOrNull()?.abs()?.takeIf { it.signum() > 0 }
}
