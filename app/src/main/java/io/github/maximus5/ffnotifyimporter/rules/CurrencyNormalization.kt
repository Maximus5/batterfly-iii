package io.github.maximus5.ffnotifyimporter.rules

/**
 * Best-effort translation of a captured currency value (often just a symbol - banks love
 * printing "€50.00" rather than "50.00 EUR") to its code, using the user-maintained
 * [CurrenciesDb] table. Anything not found in [symbolToCode] - already a code, or a symbol the
 * user hasn't added yet - passes through unchanged rather than failing.
 */
fun normalizeCurrency(raw: String, symbolToCode: Map<String, String>): String =
    symbolToCode[raw] ?: raw
