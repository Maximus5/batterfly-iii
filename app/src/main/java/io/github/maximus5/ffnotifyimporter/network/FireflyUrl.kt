package io.github.maximus5.ffnotifyimporter.network

import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.URLEncoder
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
 * source, not guessed): exact amount+currency + a [dedupSearchDateRange] window - `date_on`
 * instead of `date_after`+`date_before` when the window stays within one calendar day (the common
 * case, default 5 minutes), rather than two identical-looking date terms - AND'd with whichever
 * of [sourceName]/[destinationName] is non-null (an account *name*, never an id - deliberately
 * not scoped to a Firefly account id the way an earlier version of this search was, since this
 * app frequently only has a name for an account that was typed rather than picked from
 * `FireflyApiClient.listAssetAccounts`, and posting a transaction by name alone is already fully
 * supported - see `FireflyTransactionRequest`'s KDoc - so dedup searching by name only should be
 * too), plus [description] when [includeDescription] is true. Null if there's nothing to search
 * on at all (no amount, or neither account name known) - the caller skips the remote check
 * entirely rather than searching unscoped (amount+date alone could coincidentally match an
 * unrelated transaction).
 *
 * When [foreignAmount]/[foreignCurrencyCode] are both set, `foreign_amount_is`/
 * `foreign_currency_is` are used *instead of* `amount_is`/`currency_is` - [amount]/[currencyCode]
 * are this app's own approximate conversion (`rules.applyApproxConversion`, a fetched exchange
 * rate this app picked independently), not something Firefly's own stored amount is likely to
 * match exactly; the foreign amount/currency is the one precise, bank-reported value both sides
 * should agree on. The user's own direct call after a real false negative on a foreign-currency
 * notification.
 *
 * **Known fragility, not yet resolved** - `OperatorQuerySearch::searchAccount` (backing
 * `source_account_is`/`destination_account_is`) first resolves the given value against a *real*
 * Firefly account by that exact name, and if it finds none, calls the collector's `findNothing()`
 * - which forces the *entire* query to return zero results, not just "this term matched nothing".
 * Since [sourceName]/[destinationName] are often free-typed text (never validated against
 * Firefly's own account list), a wording mismatch here can silently zero out an otherwise-correct
 * search. Caught directly by the user testing against a real rule; a fix was proposed (drop the
 * account terms, rely on `rules.remoteCandidateMatches`'s already-correct client-side account
 * check instead) and rejected ("это полная пурга" - loses precision, too broad a server-side
 * selection) along with a second proposal (resolve the name to a real id via
 * `FireflyApiClient.listAssetAccounts` first, then query by id) rejected too ("усложнение логики и
 * никакого контроля что прошло не так"). Still open - see TODO.md.
 */
fun dedupSearchQuery(
    amount: BigDecimal?,
    currencyCode: String,
    foreignAmount: BigDecimal?,
    foreignCurrencyCode: String?,
    centerDate: Instant,
    windowMinutes: Int,
    sourceName: String?,
    destinationName: String?,
    description: String,
    includeDescription: Boolean,
): String? {
    if (sourceName == null && destinationName == null) return null
    val (start, end) = dedupSearchDateRange(centerDate, windowMinutes)
    val terms = mutableListOf<String>()
    if (foreignAmount != null && foreignCurrencyCode != null) {
        terms += "foreign_amount_is:${quoteSearchValue(foreignAmount.setScale(2, RoundingMode.HALF_UP).toPlainString())}"
        terms += "foreign_currency_is:${quoteSearchValue(foreignCurrencyCode)}"
    } else {
        if (amount == null) return null
        terms += "amount_is:${quoteSearchValue(amount.setScale(2, RoundingMode.HALF_UP).toPlainString())}"
        terms += "currency_is:${quoteSearchValue(currencyCode)}"
    }
    // The common case (window stays within one calendar day) - one date_on instead of two
    // identical date_after/date_before terms for the same day.
    if (start == end) {
        terms += "date_on:$start"
    } else {
        terms += "date_after:$start"
        terms += "date_before:$end"
    }
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

/** The web UI search page pre-filled with [query] - `{baseUrl}/search?search=<encoded query>`,
 * confirmed against Firefly's current `routes/web.php` (`search.index` ->
 * `SearchController::index`, which reads the `search` GET parameter, not assumed) - the same
 * operator-query syntax `dedupSearchQuery` builds for the API's own search endpoint. Lets
 * `ui.MessagesFragment`'s "Check if created" hand the user a working, pre-filled search page
 * instead of just showing the raw query text with nowhere to paste it. */
fun searchUrl(baseUrl: String, query: String): String =
    "${normalizeBaseUrl(baseUrl)}/search?search=${URLEncoder.encode(query, "UTF-8")}"

/** The web UI page for one created transaction (not an API path) - `transactions/show/{id}`,
 * confirmed against Firefly's own current `routes/web.php`
 * (`Route::get('show/{transactionGroup?}', ...)` under the `transactions` prefix), not assumed.
 * [id] is `POST /v1/transactions`'s returned `data.id` - the transaction *group* id, which is
 * exactly what `{transactionGroup}` expects. */
fun transactionUrl(baseUrl: String, id: String): String = "${normalizeBaseUrl(baseUrl)}/transactions/show/$id"
