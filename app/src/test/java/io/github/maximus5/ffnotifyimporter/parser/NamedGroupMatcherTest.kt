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
            """Zahlung.*?(?<amount>[\d.,]+)\s*(?<currency>[A-Z]{1,3}\$|[A-Z]{3}|[€£$¥]).*?(?:an)\s+(?<counterparty>.+?)\sgezahlt\.?$"""
        val combined = "Zahlung erfolgreich\nSie haben 45,30 € an Shop 5678 GmbH gezahlt."

        val groups = extractGroups(pattern, combined)

        assertEquals("45,30", groups?.get("amount"))
        assertEquals("€", groups?.get("currency"))
        assertEquals("Shop 5678 GmbH", groups?.get("counterparty"))
    }

    @Test
    fun `a greedy dot before the amount group can capture only its last digit, not the whole number`() {
        // Classic pitfall, found via a real rule: greedy `.*` backtracks from the end of the
        // string, so the amount group is tried starting as LATE as possible first - here that
        // means it settles for "0" (just the last digit of "45,30"), since everything after it
        // still lets the rest of the pattern succeed. The engine never even tries "45,30".
        val pattern =
            """Zahlung erfolgreich\s*.*(?<amount>[\d.,]+)\s*(?<currency>[A-Z]{1,3}\$|[A-Z]{3}|[€£$¥]).*?(?:an)\s+(?<counterparty>.+?)\.?$"""
        val combined = "Zahlung erfolgreich\nSie haben 45,30 € an Shop 5678 GmbH gezahlt."

        val groups = extractGroups(pattern, combined)

        assertEquals("0", groups?.get("amount"))
    }

    @Test
    fun `a non-digit character class instead of a greedy dot fixes the pitfall and keeps the verb out of counterparty`() {
        // RuleEditFragment.EXAMPLE_PATTERN_TITLE_ANCHORED, verbatim - the fix for the test above:
        // [^\d]* can't overshoot past the number it's skipping to (it stops at the first digit,
        // whichever digit that is), and (?:gezahlt)? before the optional trailing period stops
        // the lazy counterparty group from swallowing the sentence's closing verb.
        val pattern = """Zahlung erfolgreich[^\d]*(?<amount>[\d.,]+)\s*(?<currency>[A-Z]{1,3}\$|[A-Z]{3}|[€£$¥]).*?(?:an)\s+(?<counterparty>.+?)\s*(?:gezahlt)?\.?$"""
        val combined = "Zahlung erfolgreich\nSie haben 45,30 € an Shop 5678 GmbH gezahlt."

        val groups = extractGroups(pattern, combined)

        assertEquals("45,30", groups?.get("amount"))
        assertEquals("€", groups?.get("currency"))
        assertEquals("Shop 5678 GmbH", groups?.get("counterparty"))
    }

    @Test
    fun `currency group also matches a prefixed-dollar form like US dollar, not just a bare code or symbol`() {
        // [A-Z]{1,3}\$ (tried before the plain [A-Z]{3} alternative) - currencies like "US$",
        // "A$", "HK$" prefix the dollar sign with a short country code instead of using a bare
        // symbol or an ISO code.
        val pattern = """(?<amount>[\d.,]+)\s*(?<currency>[A-Z]{1,3}\$|[A-Z]{3}|[€£$¥])"""

        assertEquals("US$", extractGroups(pattern, "12.50 US$")?.get("currency"))
        assertEquals("A$", extractGroups(pattern, "12.50 A$")?.get("currency"))
        assertEquals("EUR", extractGroups(pattern, "12.50 EUR")?.get("currency"))
        assertEquals("¥", extractGroups(pattern, "1250 ¥")?.get("currency"))
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
