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
        sourceAccountName: String = "Bank Account A",
        destinationAccountName: String = "",
        homeCurrency: String = "EUR",
        pattern: String,
        descriptionTemplate: String = "{title}",
        notesTemplate: String = "{body}",
        categoryTemplate: String = "",
        tagsTemplate: String = "",
        enabled: Boolean = true,
    ) = ParseRule(
        id = id,
        label = label,
        packageNames = packageNames,
        kind = kind,
        sourceAccountName = sourceAccountName,
        destinationAccountName = destinationAccountName,
        homeCurrency = homeCurrency,
        pattern = pattern,
        descriptionTemplate = descriptionTemplate,
        notesTemplate = notesTemplate,
        categoryTemplate = categoryTemplate,
        tagsTemplate = tagsTemplate,
        enabled = enabled,
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
            sourceAccountName = "Bank Account A",
            pattern = """spent (?<amount>[\d,]+) EUR at (?<counterparty>.+)""",
            descriptionTemplate = "{counterparty}",
            notesTemplate = "raw: {body}",
        )
        val outcome = RuleEngine(listOf(r)).match(message()) as ParseOutcome.Matched

        assertEquals(BigDecimal("12.50"), outcome.draft.amount)
        assertEquals("EUR", outcome.draft.currencyCode)
        assertEquals("Shop1234", outcome.draft.description)
        assertEquals("raw: You spent 12,50 EUR at Shop1234", outcome.draft.notes)
        assertEquals("Bank Account A", outcome.draft.sourceName)
        assertEquals(null, outcome.draft.destinationName)
        assertTrue(outcome.draft.issues.isEmpty())
    }

    @Test
    fun `destination stays null when not set on the rule - no implicit fallback to anything`() {
        val r = rule(
            sourceAccountName = "Bank Account A",
            destinationAccountName = "",
            pattern = """spent (?<amount>[\d,]+) EUR at (?<counterparty>.+)""",
        )
        val outcome = RuleEngine(listOf(r)).match(message()) as ParseOutcome.Matched

        assertEquals(null, outcome.draft.destinationName)
    }

    @Test
    fun `destination can reference a captured group explicitly, same macro mechanism as templates`() {
        val r = rule(
            sourceAccountName = "Bank Account A",
            destinationAccountName = "{counterparty}",
            pattern = """spent (?<amount>[\d,]+) EUR at (?<counterparty>.+)""",
        )
        val outcome = RuleEngine(listOf(r)).match(message()) as ParseOutcome.Matched

        assertEquals("Shop1234", outcome.draft.destinationName)
    }

    @Test
    fun `transfer draft uses both fixed account names, ignoring any captured counterparty`() {
        val r = rule(
            kind = RuleKind.TRANSFER,
            sourceAccountName = "Bank Account A",
            destinationAccountName = "Savings EUR",
            pattern = """moved (?<amount>[\d,]+) EUR""",
        )
        val outcome = RuleEngine(listOf(r)).match(message(text = "You moved 12,50 EUR")) as ParseOutcome.Matched

        assertEquals("Bank Account A", outcome.draft.sourceName)
        assertEquals("Savings EUR", outcome.draft.destinationName)
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
    fun `appname macro renders the captured message's app label`() {
        val r = rule(
            pattern = """spent (?<amount>[\d,]+) EUR""",
            descriptionTemplate = "{appname}",
        )
        val outcome = RuleEngine(listOf(r)).match(message()) as ParseOutcome.Matched

        assertEquals("Bank App", outcome.draft.description)
    }

    @Test
    fun `category and tags templates render and tags split on comma`() {
        val r = rule(
            pattern = """spent (?<amount>[\d,]+) EUR at (?<counterparty>.+)""",
            categoryTemplate = "Groceries",
            tagsTemplate = "recurring, {counterparty} ,  ",
        )
        val outcome = RuleEngine(listOf(r)).match(message()) as ParseOutcome.Matched

        assertEquals("Groceries", outcome.draft.category)
        assertEquals(listOf("recurring", "Shop1234"), outcome.draft.tags)
    }

    @Test
    fun `blank category and tags templates produce no category and no tags`() {
        val r = rule(pattern = """spent (?<amount>[\d,]+) EUR""")
        val outcome = RuleEngine(listOf(r)).match(message()) as ParseOutcome.Matched

        assertEquals(null, outcome.draft.category)
        assertTrue(outcome.draft.tags.isEmpty())
    }

    @Test
    fun `currency symbol before the amount is normalized to its code via currencySymbols`() {
        val r = rule(
            homeCurrency = "EUR",
            pattern = """spent (?<currency>[€$£])(?<amount>[\d,]+)""",
        )
        val outcome = RuleEngine(listOf(r), currencySymbols = mapOf("€" to "EUR"))
            .match(message(text = "You spent €12,50")) as ParseOutcome.Matched

        assertEquals(BigDecimal("12.50"), outcome.draft.amount)
        assertEquals("EUR", outcome.draft.currencyCode)
        assertTrue(outcome.draft.issues.isEmpty())
    }

    @Test
    fun `an unmapped currency symbol is treated as foreign as-is`() {
        val r = rule(
            homeCurrency = "EUR",
            pattern = """spent (?<currency>[€$£])(?<amount>[\d,]+)""",
        )
        val outcome = RuleEngine(listOf(r), currencySymbols = mapOf("€" to "EUR"))
            .match(message(text = "You spent £12,50")) as ParseOutcome.Matched

        assertEquals("£", outcome.draft.foreignCurrencyCode)
        assertEquals(setOf(DraftIssue.LOCAL_AMOUNT_MISSING), outcome.draft.issues)
    }

    @Test
    fun `disabled rules are never candidates`() {
        val engine = RuleEngine(listOf(rule(pattern = """(?<amount>\d+)""", enabled = false)))
        val outcome = engine.match(message(text = "123"))
        assertEquals(ParseOutcome.NoMatch(NoMatchReason.NO_RULES_FOR_APP), outcome)
    }
}
