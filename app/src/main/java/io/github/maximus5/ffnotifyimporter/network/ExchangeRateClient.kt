package io.github.maximus5.ffnotifyimporter.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Frankfurter (api.frankfurter.dev) - a free, keyless, ECB-based exchange-rate API, picked over
 * relying on Firefly's own built-in rate table: per Firefly's own docs, its auto-download "only
 * works for the default system provided currencies" and *silently falls back to 1.0* for a pair
 * it has no rate for, with nothing in the response flagging that fallback (see TODO.md's
 * cross-currency history) - not safe to trust blind. Frankfurter instead returns a real HTTP
 * error (422) for an unsupported currency code - verified directly against the live API, not
 * assumed. No API key, no request quota (per its own docs).
 */
class ExchangeRateClient {

    sealed interface Result {
        data class Success(val rate: BigDecimal) : Result
        data class Failure(val message: String) : Result
    }

    suspend fun fetchRate(base: String, quote: String, date: Instant): Result = withContext(Dispatchers.IO) {
        try {
            withNetworkRetry {
                val connection = URL(exchangeRateUrl(BASE_URL, base, quote, date)).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                val code = connection.responseCode
                if (code in 200..299) {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val rate = parseRate(body)
                    if (rate != null) Result.Success(rate) else Result.Failure("No rate in response: $body")
                } else {
                    val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    Result.Failure(
                        "$code ${connection.responseMessage.orEmpty()}".trim() +
                            errorBody.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty(),
                    )
                }
            }
        } catch (e: IOException) {
            Result.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private companion object {
        const val BASE_URL = "https://api.frankfurter.dev/v2"
        const val TIMEOUT_MILLIS = 15_000
    }
}
