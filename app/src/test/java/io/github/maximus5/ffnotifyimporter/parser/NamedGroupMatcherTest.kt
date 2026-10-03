package io.github.maximus5.ffnotifyimporter.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NamedGroupMatcherTest {

    @Test
    fun `returns null when the pattern does not match`() {
        assertNull(extractGroups("""Zahlung (?<amount>[\d.,]+)""", "nothing relevant here"))
    }

    @Test
    fun `extracts every named group that captured something`() {
        val pattern = """Zahlung (?<amount>[\d.,]+) (?<currency>EUR|CHF) bei (?<counterparty>.+)"""
        val groups = extractGroups(pattern, "Zahlung 12,50 EUR bei Shop1234")

        assertEquals(mapOf("amount" to "12,50", "currency" to "EUR", "counterparty" to "Shop1234"), groups)
    }

    @Test
    fun `matches across title and body joined by a newline`() {
        val pattern = """Gutschrift erhalten\n.*von (?<counterparty>.+?) erhalten"""
        val combined = "Gutschrift erhalten\nSie haben eine Zahlung über 100,00 EUR von Max Mustermann erhalten."

        val groups = extractGroups(pattern, combined)

        assertEquals("Max Mustermann", groups?.get("counterparty"))
    }

    @Test
    fun `omits a declared group that matched but captured nothing`() {
        val pattern = """(?<amount>\d+)(?<currency>EUR)?"""
        val groups = extractGroups(pattern, "42")

        assertEquals(setOf("amount"), groups?.keys)
    }

    @Test
    fun `namedGroupNames lists declared groups in order`() {
        val pattern = """(?<amount>[\d.,]+) (?<currency>\w+) (?<counterparty>.+)"""
        assertEquals(listOf("amount", "currency", "counterparty"), namedGroupNames(pattern))
    }
}
