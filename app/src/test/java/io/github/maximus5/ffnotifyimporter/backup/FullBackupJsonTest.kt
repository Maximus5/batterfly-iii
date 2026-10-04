package io.github.maximus5.ffnotifyimporter.backup

import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.rules.CreatedTransactionRecord
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
            CapturedMessage("bank.app", "Bank App", "Payment", "You spent 10 EUR", 1_000L, fireflyTransactionId = "12345"),
            CapturedMessage("bank.app", "Bank App", "Payment", "You spent 20 EUR", 2_000L),
        ),
        createdTransactions = listOf(
            CreatedTransactionRecord(hash = "abc123", dateMillis = 1_000L, fireflyTransactionId = "12345"),
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
        // Unlike RulesJsonTest's sharing format: a backup restore still re-adds rules as fresh
        // rows (there's nothing meaningful to carry the old row id for), but - deliberately
        // *unlike* RulesJson - it DOES carry the Firefly account id across, since this is a
        // same-device safety net, not a cross-instance sharing format. See FullBackupData's KDoc.
        assertEquals("", imported.rules.first().id)
        assertEquals("2", imported.rules.first().sourceAccountId)
        assertEquals(original.messages, imported.messages)
        assertEquals(original.createdTransactions, imported.createdTransactions)
    }

    @Test
    fun `a message's fireflyTransactionId round-trips - restoring a backup must not lose it`() {
        val imported = fullBackupFromJson(fullBackupToJson(data()))!!

        assertEquals("12345", imported.messages.first { it.text == "You spent 10 EUR" }.fireflyTransactionId)
        assertNull(imported.messages.first { it.text == "You spent 20 EUR" }.fireflyTransactionId)
    }

    @Test
    fun `a rule's sourceAccountId and destinationAccountId round-trip - unlike RulesJson's sharing format`() {
        val rule = ParseRule(
            id = "r1",
            label = "Transfer",
            packageNames = setOf("bank.app"),
            kind = RuleKind.TRANSFER,
            sourceAccountName = "Checking",
            sourceAccountId = "2",
            destinationAccountName = "Savings",
            destinationAccountId = "7",
            homeCurrency = "EUR",
            pattern = """moved (?<amount>[\d,]+) EUR""",
        )
        val imported = fullBackupFromJson(fullBackupToJson(data().copy(rules = listOf(rule))))!!

        assertEquals("2", imported.rules.first().sourceAccountId)
        assertEquals("7", imported.rules.first().destinationAccountId)
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
        assertTrue(imported?.createdTransactions?.isEmpty() == true)
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
