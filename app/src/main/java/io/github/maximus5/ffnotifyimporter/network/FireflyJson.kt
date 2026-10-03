package io.github.maximus5.ffnotifyimporter.network

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

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
        request.sourceName?.let { put("source_name", it) }
        request.destinationName?.let { put("destination_name", it) }
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
