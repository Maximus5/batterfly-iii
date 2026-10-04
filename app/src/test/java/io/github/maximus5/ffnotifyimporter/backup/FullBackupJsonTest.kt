package io.github.maximus5.ffnotifyimporter.backup

import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.rules.CurrencyEntry
import io.github.maximus5.ffnotifyimporter.rules.ParseRule
import io.github.maximus5.ffnotifyimporter.rules.RuleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullBackupJsonTest {

    private fun data() = FullBackupData(
        monitoredPackages = setOf("bank.app", "wallet.app"),
        retentionDays = 5,
        currencies = listOf(CurrencyEntry("EUR", "€"), CurrencyEntry("CHF", "")),
        rules = listOf(
            ParseRule(
                id = "r1",
                label = "Test rule",
                packageNames = setOf("bank.app"),
                kind = RuleKind.WITHDRAWAL,
                sourceAccountName = "Bank Account A",
                sourceAccountId = "2",
                homeCurrency = "EUR",
                pattern = """spent (?<amount>[\d,]+) EUR""",
            ),
        ),
        messages = listOf(
            CapturedMessage("bank.app", "Bank App", "Payment", "You spent 10 EUR", 1_000L),
            CapturedMessage("bank.app", "Bank App", "Payment", "You spent 20 EUR", 2_000L),
        ),
    )

    @Test
    fun `round-trips every section`() {
        val original = data()
        val imported = fullBackupFromJson(fullBackupToJson(original))!!

        assertEquals(original.monitoredPackages, imported.monitoredPackages)
        assertEquals(original.retentionDays, imported.retentionDays)
        assertEquals(original.currencies, imported.currencies)
        assertEquals(1, imported.rules.size)
        assertEquals("Test rule", imported.rules.first().label)
        // Same instance-specific exclusions as RulesJsonTest - a backup restore still re-adds
        // rules as fresh rows and still can't carry a Firefly account id across re-import.
        assertEquals("", imported.rules.first().id)
        assertNull(imported.rules.first().sourceAccountId)
        assertEquals(original.messages, imported.messages)
    }

    @Test
    fun `fullBackupFromJson returns null for a file that isn't shaped like a backup at all`() {
        assertNull(fullBackupFromJson("not json"))
        assertNull(fullBackupFromJson("""{"nope": true}"""))
    }

    @Test
    fun `a missing optional section falls back to empty rather than failing the whole restore`() {
        val imported = fullBackupFromJson("""{"monitoredPackages": ["bank.app"]}""")

        assertEquals(setOf("bank.app"), imported?.monitoredPackages)
        assertEquals(0, imported?.retentionDays)
        assertTrue(imported?.currencies?.isEmpty() == true)
        assertTrue(imported?.rules?.isEmpty() == true)
        assertTrue(imported?.messages?.isEmpty() == true)
    }

    @Test
    fun `a message entry missing its timestamp is skipped, not defaulted to 0`() {
        val json = """
            {"monitoredPackages": [], "messages": [
                {"packageName": "bank.app", "title": "x", "text": "y"},
                {"packageName": "bank.app", "title": "x", "text": "y", "timestampMillis": 5000}
            ]}
        """.trimIndent()

        val imported = fullBackupFromJson(json)

        assertEquals(1, imported?.messages?.size)
        assertEquals(5000L, imported?.messages?.first()?.timestampMillis)
    }
}
