package io.github.maximus5.ffnotifyimporter.backup

import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.prefs.RetentionSettings
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
        retention = RetentionSettings(persistToDisk = true, days = 7),
        currencies = listOf(CurrencyEntry("EUR", "€"), CurrencyEntry("CHF", "")),
        rules = listOf(
            ParseRule(
                id = "r1",
                label = "Test rule",
                packageNames = setOf("bank.app"),
                kind = RuleKind.WITHDRAWAL,
                sourceAccountName = "Bank Account A",
                homeCurrency = "EUR",
                pattern = """spent (?<amount>[\d,]+) EUR""",
            ),
        ),
        messages = listOf(
            CapturedMessage("bank.app", "Bank App", "Payment", "You spent 10 EUR", 1_000L, fireflyTransactionId = "12345"),
            CapturedMessage("bank.app", "Bank App", "Payment", "You spent 20 EUR", 2_000L),
        ),
        createdTransactions = listOf(
            CreatedTransactionRecord(
                hashWithDescription = "abc123",
                hashWithoutDescription = "def456",
                dateMillis = 1_000L,
                fireflyTransactionId = "12345",
            ),
        ),
    )

    @Test
    fun `round-trips every section`() {
        val original = data()
        val imported = fullBackupFromJson(fullBackupToJson(original))!!

        assertEquals(original.monitoredPackages, imported.monitoredPackages)
        assertEquals(original.retention, imported.retention)
        assertEquals(original.currencies, imported.currencies)
        assertEquals(1, imported.rules.size)
        assertEquals("Test rule", imported.rules.first().label)
        // Same as RulesJsonTest's sharing format now: a backup restore still re-adds rules as
        // fresh rows - there's nothing meaningful to carry the old row id for either way.
        assertEquals("", imported.rules.first().id)
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
    fun `an old backup file's now-unrecognized sourceAccountId-destinationAccountId keys are harmlessly ignored`() {
        val json = """
            {"monitoredPackages": [], "rules": [
                {"label": "Transfer", "kind": "TRANSFER", "sourceAccountName": "Checking",
                 "sourceAccountId": "2", "destinationAccountName": "Savings",
                 "destinationAccountId": "7", "homeCurrency": "EUR",
                 "pattern": "moved (?<amount>[\\d,]+) EUR"}
            ]}
        """.trimIndent()

        val imported = fullBackupFromJson(json)

        assertEquals(1, imported?.rules?.size)
        assertEquals("Checking", imported?.rules?.first()?.sourceAccountName)
    }

    @Test
    fun `a createdTransactions entry from before the two-hash change falls back to the old hash key`() {
        val json = """
            {"monitoredPackages": [], "createdTransactions": [
                {"hash": "legacy123", "dateMillis": 1000, "fireflyTransactionId": "999"}
            ]}
        """.trimIndent()

        val imported = fullBackupFromJson(json)

        val record = imported?.createdTransactions?.single()
        assertEquals("legacy123", record?.hashWithDescription)
        assertNull(record?.hashWithoutDescription)
    }

    @Test
    fun `a rule with a blank homeCurrency survives a restore - it isn't silently dropped`() {
        val json = """
            {"monitoredPackages": [], "rules": [
                {"label": "Default currency", "kind": "WITHDRAWAL", "homeCurrency": "",
                 "pattern": "(?<amount>[\\d,]+)"}
            ]}
        """.trimIndent()

        val imported = fullBackupFromJson(json)

        assertEquals(1, imported?.rules?.size)
        assertEquals("", imported?.rules?.first()?.homeCurrency)
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
        assertEquals(RetentionSettings(persistToDisk = false, days = 5), imported?.retention)
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

    @Test
    fun `an old backup with only retentionDays maps onto the two-part setting`() {
        val persisted = fullBackupFromJson("""{"monitoredPackages": [], "retentionDays": 3}""")
        val inMemory = fullBackupFromJson("""{"monitoredPackages": [], "retentionDays": 0}""")

        assertEquals(RetentionSettings(persistToDisk = true, days = 3), persisted?.retention)
        assertEquals(RetentionSettings(persistToDisk = false, days = 5), inMemory?.retention)
    }

    @Test
    fun `an explicit persistHistory flag wins over the legacy reading of retentionDays`() {
        val imported = fullBackupFromJson("""{"monitoredPackages": [], "persistHistory": false, "retentionDays": 30}""")

        assertEquals(RetentionSettings(persistToDisk = false, days = 30), imported?.retention)
    }
}
