package io.github.maximus5.ffnotifyimporter.network

/**
 * What `POST /v1/transactions` needs, in Firefly's own field shapes - a plain decimal-string
 * [amount] (Firefly's own format, see its `TransactionSplitStore` schema), an ISO-8601 [dateIso].
 * [type] is Firefly's exact literal ("withdrawal"/"deposit"/"transfer"), not `rules.RuleKind` -
 * `network` has no dependency on `rules`, same "parser doesn't know about rules" layering
 * reasoning already used elsewhere (see ARCHITECTURE.md); the caller maps `RuleKind.name.lowercase()`.
 */
data class FireflyTransactionRequest(
    val type: String,
    val dateIso: String,
    val amount: String,
    val description: String,
    val currencyCode: String,
    val sourceName: String? = null,
    val destinationName: String? = null,
    val categoryName: String? = null,
    val tags: List<String> = emptyList(),
    val notes: String? = null,
    val foreignAmount: String? = null,
    val foreignCurrencyCode: String? = null,
    /** Firefly's own free-text `external_id` field - a place to stash a reference back to
     * whatever triggered this transaction on our side, independent of our own local
     * "created transactions" log (see TODO.md). */
    val externalId: String? = null,
)
