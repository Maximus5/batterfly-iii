package io.github.maximus5.ffnotifyimporter.network

import java.math.BigDecimal
import java.time.Instant

/** One transaction *split* as returned by `GET /v1/accounts/{id}/transactions` (same shape
 * `TransactionGroupTransformer` uses everywhere - verified against Firefly's current source, not
 * assumed) - [groupId] is the outer transaction group's id (what this app treats as "the"
 * transaction id everywhere else, e.g. `FireflyApiClient.createTransaction`'s own return value),
 * [sourceId]/[destinationId] are always real Firefly account ids here (Firefly auto-creates
 * expense/revenue counterparties with a real id too, unlike this app's own `TransactionDraft`
 * which only ever knows an id for the asset side of a rule - see `rules.RemoteTransactionCandidate`
 * for how that mismatch gets reconciled when matching). Used by `MessagesFragment`'s remote
 * duplicate check (see TODO.md's dedup history) - a group can have more than one split, each
 * becomes its own candidate here since this app's own transactions never split but a real one in
 * Firefly might. */
data class FireflyTransactionCandidate(
    val groupId: String,
    val date: Instant,
    val amount: BigDecimal,
    val currencyCode: String,
    val foreignAmount: BigDecimal?,
    val foreignCurrencyCode: String?,
    val description: String,
    val sourceId: String?,
    val sourceName: String?,
    val destinationId: String?,
    val destinationName: String?,
)
