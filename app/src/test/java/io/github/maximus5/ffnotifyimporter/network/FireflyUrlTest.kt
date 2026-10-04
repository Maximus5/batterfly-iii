package io.github.maximus5.ffnotifyimporter.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
