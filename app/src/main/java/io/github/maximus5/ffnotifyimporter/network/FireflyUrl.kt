package io.github.maximus5.ffnotifyimporter.network

import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
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

/** The `date_after`/`date_before` query values (plain `yyyy-MM-dd` - Firefly's search operators
 * are date-only, same granularity as every other date filter its API has) that fully cover a
 * ±[windowMinutes] window around [centerDate], for [dedupSearchQuery]. Deliberately generous
 * (whole calendar days, UTC) rather than trying to match the window's exact minute boundary
 * server-side - the precise check happens client-side afterwards (`rules.remoteCandidateMatches`)
 * against each candidate's real timestamp, this just avoids pulling unrelated history over the
 * network. */
fun dedupSearchDateRange(centerDate: Instant, windowMinutes: Int): Pair<String, String> {
    val windowMillis = windowMinutes * 60_000L
    val formatter = DateTimeFormatter.ISO_LOCAL_DATE
    val start = centerDate.minusMillis(windowMillis).atZone(ZoneOffset.UTC).format(formatter)
    val end = centerDate.plusMillis(windowMillis).atZone(ZoneOffset.UTC).format(formatter)
    return start to end
}

/**
 * The query string for `FireflyApiClient.searchTransactionsByQuery` - Firefly's own
 * operator-trigger search syntax (`field:value`/`field:"quoted value"`, built on the
 * `gdbots/query-parser` library; operator names confirmed against `OperatorQuerySearch`'s current
 * source, not guessed): exact [amount] + [currencyCode] + a [dedupSearchDateRange] window, AND'd
 * with whichever of [sourceName]/[destinationName] is non-null (an account *name*, never an id -
 * deliberately not scoped to a Firefly account id the way an earlier version of this search was,
 * since this app frequently only has a name for an account that was typed rather than picked from
 * `FireflyApiClient.listAssetAccounts`, and posting a transaction by name alone is already fully
 * supported - see `FireflyTransactionRequest`'s KDoc - so dedup searching by name only should be
 * too), plus [description] when [includeDescription] is true. Null if there's nothing to search
 * on at all (no amount, or neither account name known) - the caller skips the remote check
 * entirely rather than searching unscoped (amount+date alone could coincidentally match an
 * unrelated transaction).
 */
fun dedupSearchQuery(
    amount: BigDecimal?,
    currencyCode: String,
    centerDate: Instant,
    windowMinutes: Int,
    sourceName: String?,
    destinationName: String?,
    description: String,
    includeDescription: Boolean,
): String? {
    if (amount == null) return null
    if (sourceName == null && destinationName == null) return null
    val (start, end) = dedupSearchDateRange(centerDate, windowMinutes)
    val terms = mutableListOf(
        "amount_is:${quoteSearchValue(amount.setScale(2, RoundingMode.HALF_UP).toPlainString())}",
        "currency_is:${quoteSearchValue(currencyCode)}",
        "date_after:$start",
        "date_before:$end",
    )
    sourceName?.let { terms += "source_account_is:${quoteSearchValue(it)}" }
    destinationName?.let { terms += "destination_account_is:${quoteSearchValue(it)}" }
    if (includeDescription) terms += "description_is:${quoteSearchValue(description)}"
    return terms.joinToString(" ")
}

/** `gdbots/query-parser` splits a query on whitespace unless a value is wrapped in double quotes
 * - needed for anything that isn't a single bare token (an account name with a space, say). An
 * embedded `"` is escaped `\"`, the one case its own docs call out. */
private fun quoteSearchValue(value: String): String =
    if (value.any { it.isWhitespace() }) "\"${value.replace("\"", "\\\"")}\"" else value

/** The web UI page for one created transaction (not an API path) - `transactions/show/{id}`,
 * confirmed against Firefly's own current `routes/web.php`
 * (`Route::get('show/{transactionGroup?}', ...)` under the `transactions` prefix), not assumed.
 * [id] is `POST /v1/transactions`'s returned `data.id` - the transaction *group* id, which is
 * exactly what `{transactionGroup}` expects. */
fun transactionUrl(baseUrl: String, id: String): String = "${normalizeBaseUrl(baseUrl)}/transactions/show/$id"
