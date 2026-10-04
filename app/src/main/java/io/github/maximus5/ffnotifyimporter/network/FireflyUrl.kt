package io.github.maximus5.ffnotifyimporter.network

import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure helpers around the Firefly III base URL/API responses - kept separate from
 * [FireflyApiClient] (which needs a real network call) so this part is plain-Kotlin unit
 * testable.
 */

/** Strips a trailing slash so building "$baseUrl/api/v1/..." never ends up with a double "//". */
fun normalizeBaseUrl(url: String): String = url.trim().trimEnd('/')

/** The host portion of a base URL, for a short "connected to X" display - null if the URL isn't
 * parseable at all (the caller falls back to "not configured" in that case too). */
fun hostOf(url: String): String? = runCatching { URI(normalizeBaseUrl(url)).host }.getOrNull()

/** True for a `http://` URL - the one case that needs Android's cleartext-traffic allowance and
 * a "this isn't encrypted" warning in the UI, since everything else here assumes `https://`. */
fun isCleartextUrl(url: String): Boolean = url.trim().startsWith("http://", ignoreCase = true)

/**
 * Firefly's `GET /api/v1/about` responds `{"data": {"version": "...", ...}}` - pulls just the
 * version string out, or null if the body isn't shaped like that (used to confirm a successful
 * test-connection call actually talked to a real Firefly instance, not just got a 200 from
 * something else answering on that URL).
 */
fun parseAboutVersion(body: String): String? = try {
    JSONObject(body).optJSONObject("data")?.optString("version")?.takeIf { it.isNotBlank() }
} catch (e: JSONException) {
    null
}

/** The `start`/`end` query values (plain `yyyy-MM-dd`, Firefly's own date-only filter granularity
 * - confirmed against `Account/ListController::transactions()`'s source, not assumed) that fully
 * cover a ±[windowMinutes] window around [centerDate], for `FireflyApiClient.
 * searchNearbyTransactions`. Deliberately generous (whole calendar days, UTC) rather than trying
 * to match the window's exact minute boundary server-side - the precise check happens
 * client-side afterwards (`rules.remoteCandidateMatches`) against each candidate's real
 * timestamp, this just avoids pulling the account's entire history over the network. */
fun dedupSearchDateRange(centerDate: Instant, windowMinutes: Int): Pair<String, String> {
    val windowMillis = windowMinutes * 60_000L
    val formatter = DateTimeFormatter.ISO_LOCAL_DATE
    val start = centerDate.minusMillis(windowMillis).atZone(ZoneOffset.UTC).format(formatter)
    val end = centerDate.plusMillis(windowMillis).atZone(ZoneOffset.UTC).format(formatter)
    return start to end
}

/** The web UI page for one created transaction (not an API path) - `transactions/show/{id}`,
 * confirmed against Firefly's own current `routes/web.php`
 * (`Route::get('show/{transactionGroup?}', ...)` under the `transactions` prefix), not assumed.
 * [id] is `POST /v1/transactions`'s returned `data.id` - the transaction *group* id, which is
 * exactly what `{transactionGroup}` expects. */
fun transactionUrl(baseUrl: String, id: String): String = "${normalizeBaseUrl(baseUrl)}/transactions/show/$id"
