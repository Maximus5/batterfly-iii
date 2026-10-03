package io.github.maximus5.ffnotifyimporter.rules

import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class RuleEngineTest {

    private fun rule(
        id: String = "r1",
        label: String = "Test rule",
        packageNames: Set<String> = emptySet(),
        kind: RuleKind = RuleKind.WITHDRAWAL,
        accountName: String = "Bank Account A",
        homeCurrency: String = "EUR",
        counterpartyAccountName: String? = null,
        pattern: String,
        decimalSeparator: Char = ',',
        descriptionTemplate: String = "{title}",
        notesTemplate: String = "{body}",
        enabled: Boolean = true,
    ) = ParseRule(
        id, label, packageNames, kind, accountName, homeCurrency, counterpartyAccountName,
        pattern, decimalSeparator, descriptionTemplate, notesTemplate, enabled,
    )

    private fun message(
        packageName: String = "bank.app",
        title: String = "Payment",
        text: String = "You spent 12,50 EUR at Shop1234",
    ) = CapturedMessage(packageName, "Bank App", title, text, timestampMillis = 1000)

    @Test
    fun `no rules for the app`() {
        val engine = RuleEngine(listOf(rule(packageNames = setOf("other.app"), pattern = """(?<amount>\d+)""")))
        val outcome = engine.match(message(packageName = "bank.app"))
        assertEquals(ParseOutcome.NoMatch(NoMatchReason.NO_RULES_FOR_APP), outcome)
    }

    @Test
    fun `no pattern matched`() {
        val engine = RuleEngine(listOf(rule(pattern = """nope (?<amount>\d+)""")))
        val outcome = engine.match(message())
        assertEquals(ParseOutcome.NoMatch(NoMatchReason.NO_PATTERN_MATCHED), outcome)
    }

    @Test
    fun `pattern matched but amount is unparsable`() {
        val engine = RuleEngine(listOf(rule(pattern = """spent (?<amount>[A-Za-z]+) EUR""")))
        val outcome = engine.match(message(text = "You spent many EUR at Shop1234"))
        assertEquals(ParseOutcome.NoMatch(NoMatchReason.AMOUNT_UNPARSABLE), outcome)
    }

    @Test
    fun `first matching rule in list order wins, later rules are not tried`() {
        val first = rule(id = "first", pattern = """spent (?<amount>[\d,]+) EUR""")
        val second = rule(id = "second", pattern = """spent (?<amount>[\d,]+) EUR""")
        val engine = RuleEngine(listOf(first, second))

        val outcome = engine.match(message()) as ParseOutcome.Matched
        assertEquals("first", outcome.rule.id)
    }

    @Test
    fun `builds a withdrawal draft with description and notes rendered from templates`() {
        val r = rule(
            pattern = """spent (?<amount>[\d,]+) EUR at (?<counterparty>.+)""",
            descriptionTemplate = "{counterparty}",
            notesTemplate = "raw: {body}",
        )
        val outcome = RuleEngine(listOf(r)).match(message()) as ParseOutcome.Matched

        assertEquals(BigDecimal("12.50"), outcome.draft.amount)
        assertEquals("EUR", outcome.draft.currencyCode)
        assertEquals("Shop1234", outcome.draft.description)
        assertEquals("raw: You spent 12,50 EUR at Shop1234", outcome.draft.notes)
        assertEquals("Shop1234", outcome.draft.counterpartyName)
        assertTrue(outcome.draft.issues.isEmpty())
    }

    @Test
    fun `foreign currency without an explicit foreign amount flags LOCAL_AMOUNT_MISSING`() {
        val r = rule(
            homeCurrency = "EUR",
            pattern = """spent (?<amount>[\d,]+) (?<currency>GBP)""",
        )
        val outcome = RuleEngine(listOf(r)).match(message(text = "You spent 10,00 GBP")) as ParseOutcome.Matched

        assertEquals(null, outcome.draft.amount)
        assertEquals(BigDecimal("10.00"), outcome.draft.foreignAmount)
        assertEquals("GBP", outcome.draft.foreignCurrencyCode)
        assertEquals(setOf(DraftIssue.LOCAL_AMOUNT_MISSING), outcome.draft.issues)
    }

    @Test
    fun `an explicit foreign amount alongside the home amount is trusted as-is`() {
        val r = rule(
            homeCurrency = "EUR",
            pattern = """charged (?<amount>[\d,]+) EUR \((?<foreignAmount>[\d,]+) (?<foreignCurrency>GBP)\)""",
        )
        val outcome = RuleEngine(listOf(r))
            .match(message(text = "You were charged 11,50 EUR (10,00 GBP)")) as ParseOutcome.Matched

        assertEquals(BigDecimal("11.50"), outcome.draft.amount)
        assertEquals(BigDecimal("10.00"), outcome.draft.foreignAmount)
        assertEquals("GBP", outcome.draft.foreignCurrencyCode)
        assertTrue(outcome.draft.issues.isEmpty())
    }

    @Test
    fun `disabled rules are never candidates`() {
        val engine = RuleEngine(listOf(rule(pattern = """(?<amount>\d+)""", enabled = false)))
        val outcome = engine.match(message(text = "123"))
        assertEquals(ParseOutcome.NoMatch(NoMatchReason.NO_RULES_FOR_APP), outcome)
    }
}
