package io.github.maximus5.ffnotifyimporter.posting

import android.content.Context
import io.github.maximus5.ffnotifyimporter.network.FireflyApiClient
import io.github.maximus5.ffnotifyimporter.network.FireflyCurrency
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.rules.CurrenciesDb
import io.github.maximus5.ffnotifyimporter.rules.CurrencyEntry
import io.github.maximus5.ffnotifyimporter.rules.TransactionDraft

private val CURRENCY_CODE = Regex("[A-Za-z]{3}")

/** Currency values in [draft] that are still a raw symbol after `RuleEngine`'s normalization -
 * `normalizeCurrency` passes an unknown symbol through unchanged, so "not a 3-letter code" is how
 * a value nobody translated is told apart from one that already is a code. Blank is skipped
 * (nothing was captured, nothing to look up). */
fun unresolvedCurrencySymbols(draft: TransactionDraft): Set<String> =
    listOfNotNull(draft.currencyCode, draft.foreignCurrencyCode)
        .filter { it.isNotBlank() && !CURRENCY_CODE.matches(it) }
        .toSet()

/**
 * The `CurrenciesDb` row to add for [symbol], or null if Firefly can't settle it unambiguously:
 * only *enabled* currencies count (an amount in a currency the user's Firefly doesn't have switched
 * on can't be posted anyway), and exactly one of them must carry that symbol - a shared symbol
 * (several dollars) is left for the user to map by hand rather than guessed. Also null when
 * [known] already has a row for that code: `CurrenciesDb` holds one symbol per code, so adding
 * would silently overwrite the user's own mapping.
 */
fun learnableSymbolEntry(symbol: String, fireflyCurrencies: List<FireflyCurrency>, known: List<CurrencyEntry>): CurrencyEntry? {
    val only = fireflyCurrencies.filter { it.enabled && it.symbol == symbol }.singleOrNull() ?: return null
    if (known.any { it.code.equals(only.code, ignoreCase = true) }) return null
    return CurrencyEntry(only.code.uppercase(), symbol)
}

/** Looks [symbols] up in Firefly's own currency list (one read-only `GET /v1/currencies` crawl for
 * the whole set) and adds whatever [learnableSymbolEntry] accepts to `CurrenciesDb`. True if
 * anything was added. A failed request is swallowed - same as not having looked. */
suspend fun learnCurrencySymbols(context: Context, prefs: FireflyPrefs, symbols: Set<String>): Boolean {
    if (symbols.isEmpty()) return false
    val result = prefs.apiClient().listCurrencies()
    val fireflyCurrencies = (result as? FireflyApiClient.Result.Success)?.value ?: return false
    val db = CurrenciesDb.getInstance(context)
    var learned = false
    for (symbol in symbols) {
        val entry = learnableSymbolEntry(symbol, fireflyCurrencies, db.loadAll()) ?: continue
        db.add(entry)
        learned = true
    }
    return learned
}
