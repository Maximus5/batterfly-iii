package io.github.maximus5.ffnotifyimporter.network

/**
 * What `POST /v1/transactions` needs, in Firefly's own field shapes - a plain decimal-string
 * [amount] (Firefly's own format, see its `TransactionSplitStore` schema), an ISO-8601 [dateIso].
 * [type] is Firefly's exact literal ("withdrawal"/"deposit"/"transfer"), not `rules.RuleKind` -
 * `network` has no dependency on `rules`, same "parser doesn't know about rules" layering
 * reasoning already used elsewhere (see ARCHITECTURE.md); the caller maps `RuleKind.name.lowercase()`.
 *
 * [sourceId]/[destinationId] take priority over [sourceName]/[destinationName] when both are set
 * for the same side (see [FireflyJson.buildTransactionRequestBody]) - an ID survives a Firefly
 * account rename, a name alone doesn't. The caller (`RuleEditFragment`'s asset-account picker)
 * only ever has an ID for the *asset* side of a rule; the counterparty side Firefly auto-creates
 * by name is always `...Name`-only.
 */
data class FireflyTransactionRequest(
    val type: String,
    val dateIso: String,
    val amount: String,
    val description: String,
    val currencyCode: String,
    val sourceId: String? = null,
    val sourceName: String? = null,
    val destinationId: String? = null,
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
