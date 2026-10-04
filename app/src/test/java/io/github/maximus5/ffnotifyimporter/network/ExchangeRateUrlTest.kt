package io.github.maximus5.ffnotifyimporter.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class ExchangeRateUrlTest {

    @Test
    fun `builds the rate URL with lowercased codes and the ISO date`() {
        val date = Instant.parse("2026-10-03T12:00:00Z")

        val url = exchangeRateUrl("https://api.frankfurter.dev/v2", "CHF", "EUR", date)

        assertEquals("https://api.frankfurter.dev/v2/rate/chf/eur?date=2026-10-03", url)
    }

    @Test
    fun `parses the rate field from a real-shaped response`() {
        val body = """{"date":"2026-10-03","base":"CHF","quote":"EUR","rate":1.0658}"""

        assertEquals(BigDecimal("1.0658"), parseRate(body))
    }

    @Test
    fun `returns null for a response with no rate field`() {
        assertNull(parseRate("""{"status":422,"message":"invalid currency: ABC"}"""))
    }

    @Test
    fun `returns null for malformed JSON`() {
        assertNull(parseRate("not json"))
    }
}
