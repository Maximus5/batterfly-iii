package io.github.maximus5.ffnotifyimporter.network

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * Pure request-building/response-parsing for the accounts-list and create-transaction calls -
 * kept separate from [FireflyApiClient] (which needs a real network call) so this part is
 * plain-Kotlin unit testable, same reasoning as [FireflyUrl].
 */

/** `GET /v1/accounts`'s body is `{"data": [{"id": "...", "attributes": {"name": "...", ...}},
 * ...], "meta": {...}}` - pulls out just id+name, skipping any entry missing either (malformed
 * rather than failing the whole list). */
fun parseAccountList(body: String): List<FireflyAccount> = try {
    val data = JSONObject(body).optJSONArray("data")
    if (data == null) {
        emptyList()
    } else {
        (0 until data.length()).mapNotNull { i ->
            val entry = data.optJSONObject(i) ?: return@mapNotNull null
            val id = entry.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = entry.optJSONObject("attributes")?.optString("name")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            FireflyAccount(id, name)
        }
    }
} catch (e: JSONException) {
    emptyList()
}

/** `meta.pagination.total_pages` - defaults to 1 (i.e. "no more pages") for anything that isn't
 * shaped the way a real paginated response is, so a malformed response doesn't spin forever. */
fun totalPages(body: String): Int = try {
    JSONObject(body).optJSONObject("meta")?.optJSONObject("pagination")?.optInt("total_pages", 1) ?: 1
} catch (e: JSONException) {
    1
}

/**
 * Builds the JSON body `POST /v1/transactions` expects - a `transactions` array wrapping a single
 * split (Firefly supports splitting one transaction into several, this client never does), plus
 * `error_if_duplicate_hash: true` as a free extra safety net on top of this app's own dedup check
 * (see TODO.md) - Firefly rejects the request outright if *it* also thinks this is a duplicate.
 */
fun buildTransactionRequestBody(request: FireflyTransactionRequest): String {
    val split = JSONObject().apply {
        put("type", request.type)
        put("date", request.dateIso)
        put("amount", request.amount)
        put("description", request.description)
        put("currency_code", request.currencyCode)
        // Firefly's own docs: "Submit either this or source_name" - an ID, when we have one,
        // survives a Firefly-side account rename that would silently break a name-only match, so
        // it's sent *instead of* the name, not alongside it.
        if (request.sourceId != null) put("source_id", request.sourceId) else request.sourceName?.let { put("source_name", it) }
        if (request.destinationId != null) {
            put("destination_id", request.destinationId)
        } else {
            request.destinationName?.let { put("destination_name", it) }
        }
        request.categoryName?.let { put("category_name", it) }
        if (request.tags.isNotEmpty()) put("tags", JSONArray(request.tags))
        request.notes?.let { put("notes", it) }
        request.foreignAmount?.let { put("foreign_amount", it) }
        request.foreignCurrencyCode?.let { put("foreign_currency_code", it) }
        request.externalId?.let { put("external_id", it) }
    }
    return JSONObject()
        .put("error_if_duplicate_hash", true)
        .put("transactions", JSONArray().put(split))
        .toString()
}

/** `POST /v1/transactions`'s success body is `{"data": {"id": "...", ...}}` - the created
 * transaction's ID, for this app's own "created transactions" log. */
fun parseCreatedTransactionId(body: String): String? = try {
    JSONObject(body).optJSONObject("data")?.optString("id")?.takeIf { it.isNotBlank() }
} catch (e: JSONException) {
    null
}

/** `GET /v1/accounts/{id}/transactions`'s body is `{"data": [{"id": "<group id>", "attributes":
 * {"transactions": [{"date", "amount", "currency_code", "foreign_amount",
 * "foreign_currency_code", "description", "source_id", "source_name", "destination_id",
 * "destination_name", ...}, ...]}}, ...], "meta": {...}}` - exact field names confirmed against
 * `TransactionGroupTransformer`'s current source, not assumed. Flattens every group's splits into
 * one list (each keeping its own group's id - this app's own transactions never split, but
 * Firefly/a manual entry might), skipping anything missing a field this app actually needs
 * (malformed entry, not the whole list). */
fun parseTransactionList(body: String): List<FireflyTransactionCandidate> = try {
    val data = JSONObject(body).optJSONArray("data")
    if (data == null) {
        emptyList()
    } else {
        (0 until data.length()).flatMap { i ->
            val group = data.optJSONObject(i) ?: return@flatMap emptyList()
            val groupId = group.optString("id").takeIf { it.isNotBlank() } ?: return@flatMap emptyList()
            val splits = group.optJSONObject("attributes")?.optJSONArray("transactions") ?: return@flatMap emptyList()
            (0 until splits.length()).mapNotNull { j -> parseTransactionSplit(groupId, splits.optJSONObject(j)) }
        }
    }
} catch (e: JSONException) {
    emptyList()
}

/** `GET /v1/transactions/{id}`'s body is `{"data": {"id": "<group id>", "attributes": {...same
 * shape as one entry of [parseTransactionList]'s "data" array...}}}` - a single object, not an
 * array (`ShowController::show` wraps the result in a Fractal `Item`, not a `Collection` -
 * confirmed against its current source, not assumed). Used to fetch the full fields of a
 * *locally* found duplicate (`CreatedTransactionsDb` only ever returns an id), so the
 * duplicate-confirm dialog can show real info for a local match the same way it already can for a
 * remote one. Only the first split, same "this app's own transactions never split" reasoning as
 * [parseTransactionList] - a locally-recorded id is always something this app itself created. */
fun parseSingleTransaction(body: String): FireflyTransactionCandidate? = try {
    val group = JSONObject(body).optJSONObject("data")
    val groupId = group?.optString("id")?.takeIf { it.isNotBlank() }
    val splits = group?.optJSONObject("attributes")?.optJSONArray("transactions")
    if (groupId == null || splits == null || splits.length() == 0) {
        null
    } else {
        parseTransactionSplit(groupId, splits.optJSONObject(0))
    }
} catch (e: JSONException) {
    null
}

private fun parseTransactionSplit(groupId: String, split: JSONObject?): FireflyTransactionCandidate? {
    if (split == null) return null
    val dateRaw = split.optString("date").takeIf { it.isNotBlank() } ?: return null
    // Carbon's toAtomString() - ISO-8601 *with* a numeric offset (+01:00), not the bare
    // Z-suffix form Instant.parse expects - hence OffsetDateTime.parse(...).toInstant().
    val date = try {
        OffsetDateTime.parse(dateRaw).toInstant()
    } catch (e: java.time.format.DateTimeParseException) {
        return null
    }
    val amount = split.optString("amount").toBigDecimalOrNull() ?: return null
    val currencyCode = split.optString("currency_code").takeIf { it.isNotBlank() } ?: return null
    return FireflyTransactionCandidate(
        groupId = groupId,
        date = date,
        amount = amount,
        currencyCode = currencyCode,
        foreignAmount = split.optString("foreign_amount").takeIf { it.isNotBlank() }?.toBigDecimalOrNull(),
        foreignCurrencyCode = split.optString("foreign_currency_code").takeIf { it.isNotBlank() },
        description = split.optString("description", ""),
        sourceId = split.optString("source_id").takeIf { it.isNotBlank() && it != "0" },
        sourceName = split.optString("source_name").takeIf { it.isNotBlank() },
        destinationId = split.optString("destination_id").takeIf { it.isNotBlank() && it != "0" },
        destinationName = split.optString("destination_name").takeIf { it.isNotBlank() },
    )
}

private fun String.toBigDecimalOrNull(): BigDecimal? = try {
    BigDecimal(this)
} catch (e: NumberFormatException) {
    null
}
