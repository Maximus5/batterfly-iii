package io.github.maximus5.ffnotifyimporter.network

import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure helpers for Frankfurter's rate endpoint - kept separate from [ExchangeRateClient] (which
 * needs a real network call) so this part is plain-Kotlin unit testable, same reasoning as
 * [FireflyUrl]/[FireflyJson].
 */

/** `/rate/{base}/{quote}?date=...` - Frankfurter's own docs/examples use lowercase codes; this
 * app's currency codes are uppercase (ISO convention, [io.github.maximus5.ffnotifyimporter.rules.normalizeCurrency]) -
 * lowercased here rather than assuming the API is case-insensitive. [date] is the notification's
 * own date, not "today", so the approximated amount reflects the rate on the day the payment
 * actually happened - a weekend/holiday date doesn't error, confirmed against the live API. */
fun exchangeRateUrl(baseUrl: String, base: String, quote: String, date: Instant): String {
    val dateParam = DateTimeFormatter.ISO_LOCAL_DATE.format(date.atZone(ZoneOffset.UTC).toLocalDate())
    return "$baseUrl/rate/${base.lowercase()}/${quote.lowercase()}?date=$dateParam"
}

/** `{"date":"2026-10-03","base":"CHF","quote":"EUR","rate":1.0658}` - verified against the live
 * API, not assumed. Reads `rate` as a string (`optString`, not `optDouble`) to avoid a
 * double-precision round trip on a value about to be multiplied against a real money amount. */
fun parseRate(body: String): BigDecimal? = try {
    JSONObject(body).optString("rate").takeIf { it.isNotBlank() }?.let {
        try {
            BigDecimal(it)
        } catch (e: NumberFormatException) {
            null
        }
    }
} catch (e: JSONException) {
    null
}
