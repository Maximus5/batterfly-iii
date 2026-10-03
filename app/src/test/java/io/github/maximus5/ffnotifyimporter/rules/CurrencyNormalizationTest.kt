package io.github.maximus5.ffnotifyimporter.rules

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencyNormalizationTest {

    private val symbolToCode = mapOf("€" to "EUR", "$" to "USD", "£" to "GBP")

    @Test
    fun `known symbol translates to its code`() {
        assertEquals("EUR", normalizeCurrency("€", symbolToCode))
    }

    @Test
    fun `already a code passes through unchanged`() {
        assertEquals("EUR", normalizeCurrency("EUR", symbolToCode))
    }

    @Test
    fun `unknown symbol passes through unchanged rather than failing`() {
        assertEquals("¥", normalizeCurrency("¥", symbolToCode))
    }
}
