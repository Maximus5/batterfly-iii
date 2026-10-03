package io.github.maximus5.ffnotifyimporter.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin wrapper over Firefly III's REST API - plain [HttpURLConnection] + `org.json`
 * ([FireflyUrl]'s [parseAboutVersion]), not OkHttp/Retrofit. Same "avoid a new dependency when
 * the framework already covers it" reasoning already used for the planned rules export/import
 * JSON format (see TODO.md) - a handful of endpoints doesn't justify a full HTTP client library.
 */
class FireflyApiClient(baseUrl: String, private val token: String) {

    private val baseUrl = normalizeBaseUrl(baseUrl)

    sealed interface Result<out T> {
        data class Success<T>(val value: T) : Result<T>
        data class Failure(val message: String) : Result<Nothing>
    }

    /**
     * Hits `GET /api/v1/about` - the lightest authenticated endpoint Firefly has - to confirm the
     * URL/token actually work before the user saves them. Returns the reported Firefly version on
     * success, purely so "Test connection" can show something more convincing than just "OK".
     */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val connection = openConnection("/api/v1/about", "GET")
            val code = connection.responseCode
            if (code in 200..299) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                Result.Success(parseAboutVersion(body).orEmpty())
            } else {
                Result.Failure(errorMessage(connection, code))
            }
        } catch (e: IOException) {
            Result.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Every asset account (`?type=asset`) the user has in Firefly - the rule editor's planned
     * asset-account picker source (see TODO.md): Firefly only auto-creates the *counterparty*
     * side of a transaction by name, the asset side must already exist, so a rule's asset-side
     * field needs to pick from this list rather than stay free text. Follows `meta.pagination`
     * across every page rather than assuming the first 50 is everything.
     */
    suspend fun listAssetAccounts(): Result<List<FireflyAccount>> = withContext(Dispatchers.IO) {
        try {
            val accounts = mutableListOf<FireflyAccount>()
            var page = 1
            var totalPagesSeen: Int
            do {
                val connection = openConnection("/api/v1/accounts?type=asset&page=$page", "GET")
                val code = connection.responseCode
                if (code !in 200..299) return@withContext Result.Failure(errorMessage(connection, code))
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                accounts += parseAccountList(body)
                totalPagesSeen = totalPages(body)
                page++
            } while (page <= totalPagesSeen)
            Result.Success(accounts)
        } catch (e: IOException) {
            Result.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    /** `POST /v1/transactions` - returns the created transaction's ID on success, for this app's
     * own "created transactions" log (dedup + "already created" state, see TODO.md). */
    suspend fun createTransaction(request: FireflyTransactionRequest): Result<String> = withContext(Dispatchers.IO) {
        try {
            val connection = openConnection("/api/v1/transactions", "POST")
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(buildTransactionRequestBody(request).toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code in 200..299) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val id = parseCreatedTransactionId(body)
                if (id != null) Result.Success(id) else Result.Failure("No transaction id in response: $body")
            } else {
                Result.Failure(errorMessage(connection, code))
            }
        } catch (e: IOException) {
            Result.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun openConnection(path: String, method: String): HttpURLConnection {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/vnd.api+json")
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        return connection
    }

    private fun errorMessage(connection: HttpURLConnection, code: Int): String {
        val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return "$code ${connection.responseMessage.orEmpty()}".trim() +
            errorBody.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
    }
}
