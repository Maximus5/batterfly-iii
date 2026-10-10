package io.github.maximus5.ffnotifyimporter.network

/** One currency as returned by `GET /v1/currencies` - [enabled] (confirmed against
 * `CurrencyTransformer`'s current source field name, not assumed) is the one this app actually
 * cares about: the endpoint itself returns every currency Firefly knows about, enabled or not, no
 * server-side filter - `ui.TransactionCreationFragment`'s "Default account currency" picker filters
 * to `enabled` client-side, since posting an amount in a currency the user's own Firefly instance
 * doesn't have switched on would just fail there anyway. [name]/[symbol] (same transformer, confirmed
 * the same way) feed the Currencies settings picker and `posting.learnCurrencySymbols`. */
data class FireflyCurrency(val code: String, val enabled: Boolean, val name: String = "", val symbol: String = "")
