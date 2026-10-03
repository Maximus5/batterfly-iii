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
    fun `a plain dot also crosses the title-body newline, no literal newline needed in the pattern`() {
        // Real case that prompted DOT_MATCHES_ALL: "Zahlung" is in the title, the amount is on
        // the body line - without DOTALL, `.*?` can't reach past the title's own line ending.
        val pattern =
            """Zahlung.*?(?<amount>[\d.,]+)\s*(?<currency>[A-Z]{3}|[€£$]).*?(?:an)\s+(?<counterparty>.+?)\sgezahlt\.?$"""
        val combined = "Zahlung erfolgreich\nSie haben 45,30 € an Shop 5678 GmbH gezahlt."

        val groups = extractGroups(pattern, combined)

        assertEquals("45,30", groups?.get("amount"))
        assertEquals("€", groups?.get("currency"))
        assertEquals("Shop 5678 GmbH", groups?.get("counterparty"))
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
