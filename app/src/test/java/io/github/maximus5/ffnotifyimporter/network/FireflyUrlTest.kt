package io.github.maximus5.ffnotifyimporter.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class FireflyUrlTest {

    @Test
    fun `normalizeBaseUrl strips a trailing slash`() {
        assertEquals("https://firefly.example.com", normalizeBaseUrl("https://firefly.example.com/"))
        assertEquals("https://firefly.example.com", normalizeBaseUrl("https://firefly.example.com"))
    }

    @Test
    fun `normalizeBaseUrl trims surrounding whitespace`() {
        assertEquals("https://firefly.example.com", normalizeBaseUrl("  https://firefly.example.com  "))
    }

    @Test
    fun `hostOf extracts the host from a well-formed URL`() {
        assertEquals("firefly.example.com", hostOf("https://firefly.example.com/"))
        assertEquals("192.168.1.50", hostOf("http://192.168.1.50:8080"))
    }

    @Test
    fun `hostOf returns null for an unparseable URL`() {
        assertNull(hostOf("not a url"))
        assertNull(hostOf(""))
    }

    @Test
    fun `isCleartextUrl flags http but not https`() {
        assertTrue(isCleartextUrl("http://firefly.example.com"))
        assertTrue(isCleartextUrl("HTTP://firefly.example.com"))
        assertFalse(isCleartextUrl("https://firefly.example.com"))
        assertFalse(isCleartextUrl(""))
    }

    @Test
    fun `parseAboutVersion extracts the version from a real about response`() {
        val body = """{"data":{"version":"6.5.5","api_version":"3.0.0"}}"""
        assertEquals("6.5.5", parseAboutVersion(body))
    }

    @Test
    fun `parseAboutVersion returns null for a blank version or malformed body`() {
        assertNull(parseAboutVersion("""{"data":{"version":""}}"""))
        assertNull(parseAboutVersion("not json at all"))
        assertNull(parseAboutVersion("""{"nope":true}"""))
    }

    @Test
    fun `transactionUrl builds the web UI show page, not an API path`() {
        assertEquals("https://firefly.example.com/transactions/show/42", transactionUrl("https://firefly.example.com", "42"))
    }

    @Test
    fun `transactionUrl normalizes a trailing slash on the base URL`() {
        assertEquals("https://firefly.example.com/transactions/show/42", transactionUrl("https://firefly.example.com/", "42"))
    }

    @Test
    fun `dedupSearchDateRange covers a window that stays within one day`() {
        // 2026-03-01T12:00:00Z +/- 5 minutes never crosses midnight UTC.
        val (start, end) = dedupSearchDateRange(Instant.parse("2026-03-01T12:00:00Z"), 5)
        assertEquals("2026-03-01", start)
        assertEquals("2026-03-01", end)
    }

    @Test
    fun `dedupSearchDateRange spans two days when the window crosses midnight`() {
        val (start, end) = dedupSearchDateRange(Instant.parse("2026-03-01T00:02:00Z"), 5)
        assertEquals("2026-02-28", start)
        assertEquals("2026-03-01", end)
    }

    private val searchDate = Instant.parse("2026-03-01T12:00:00Z")

    @Test
    fun `dedupSearchQuery builds every term when everything is known`() {
        val query = dedupSearchQuery(
            amount = BigDecimal("12.5"),
            currencyCode = "EUR",
            centerDate = searchDate,
            windowMinutes = 5,
            sourceName = "Bank Account A",
            destinationName = "Shop1234",
            description = "Shop1234 City",
            includeDescription = true,
        )

        assertEquals(
            "amount_is:12.50 currency_is:EUR date_after:2026-03-01 date_before:2026-03-01 " +
                "source_account_is:\"Bank Account A\" destination_account_is:Shop1234 description_is:\"Shop1234 City\"",
            query,
        )
    }

    @Test
    fun `dedupSearchQuery canonicalizes the amount to two decimal places`() {
        val query = dedupSearchQuery(
            amount = BigDecimal("12.5"),
            currencyCode = "EUR",
            centerDate = searchDate,
            windowMinutes = 5,
            sourceName = "Bank Account A",
            destinationName = null,
            description = "x",
            includeDescription = false,
        )

        assertTrue(query!!.contains("amount_is:12.50"))
    }

    @Test
    fun `dedupSearchQuery omits description_is when includeDescription is false`() {
        val query = dedupSearchQuery(
            amount = BigDecimal("12.50"),
            currencyCode = "EUR",
            centerDate = searchDate,
            windowMinutes = 5,
            sourceName = "Bank Account A",
            destinationName = null,
            description = "Shop1234 City",
            includeDescription = false,
        )

        assertFalse(query!!.contains("description_is"))
    }

    @Test
    fun `dedupSearchQuery only includes whichever side's name is known`() {
        val sourceOnly = dedupSearchQuery(
            amount = BigDecimal("12.50"), currencyCode = "EUR", centerDate = searchDate, windowMinutes = 5,
            sourceName = "Bank Account A", destinationName = null, description = "x", includeDescription = false,
        )
        assertTrue(sourceOnly!!.contains("source_account_is"))
        assertFalse(sourceOnly.contains("destination_account_is"))

        val destinationOnly = dedupSearchQuery(
            amount = BigDecimal("12.50"), currencyCode = "EUR", centerDate = searchDate, windowMinutes = 5,
            sourceName = null, destinationName = "Shop1234", description = "x", includeDescription = false,
        )
        assertFalse(destinationOnly!!.contains("source_account_is"))
        assertTrue(destinationOnly.contains("destination_account_is"))
    }

    @Test
    fun `dedupSearchQuery is null when neither account name nor the amount is known`() {
        assertNull(
            dedupSearchQuery(
                amount = BigDecimal("12.50"), currencyCode = "EUR", centerDate = searchDate, windowMinutes = 5,
                sourceName = null, destinationName = null, description = "x", includeDescription = false,
            ),
        )
        assertNull(
            dedupSearchQuery(
                amount = null, currencyCode = "EUR", centerDate = searchDate, windowMinutes = 5,
                sourceName = "Bank Account A", destinationName = null, description = "x", includeDescription = false,
            ),
        )
    }

    @Test
    fun `dedupSearchQuery quotes a value containing whitespace, leaves a bare token alone`() {
        val query = dedupSearchQuery(
            amount = BigDecimal("12.50"), currencyCode = "EUR", centerDate = searchDate, windowMinutes = 5,
            sourceName = "Loyalty Program A", destinationName = "Shop1234", description = "x", includeDescription = false,
        )

        assertTrue(query!!.contains("source_account_is:\"Loyalty Program A\""))
        assertTrue(query.contains("destination_account_is:Shop1234"))
    }

    @Test
    fun `dedupSearchQuery escapes an embedded double quote in a value`() {
        val query = dedupSearchQuery(
            amount = BigDecimal("12.50"), currencyCode = "EUR", centerDate = searchDate, windowMinutes = 5,
            sourceName = "The \"Best\" Cafe", destinationName = null, description = "x", includeDescription = false,
        )

        assertTrue(query!!.contains("source_account_is:\"The \\\"Best\\\" Cafe\""))
    }
}
