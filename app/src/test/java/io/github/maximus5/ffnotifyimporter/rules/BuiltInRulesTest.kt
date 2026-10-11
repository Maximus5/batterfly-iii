package io.github.maximus5.ffnotifyimporter.rules

import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class BuiltInRulesTest {

    private fun message(title: String = "Payment", text: String) =
        CapturedMessage("some.bank.app", "Some Bank", title, text, timestampMillis = 1000)

    @Test
    fun `currency-before-amount rule matches a symbol-then-amount notification`() {
        val engine = RuleEngine(
            listOf(BuiltInRules.CURRENCY_BEFORE_AMOUNT),
            currencySymbols = mapOf("€" to "EUR"),
            defaultAccountCurrency = "EUR",
        )
        val outcome = engine.match(message(text = "You paid €100.50 at Shop1234")) as ParseOutcome.Matched

        assertEquals(BigDecimal("100.50"), outcome.draft.amount)
        assertEquals("EUR", outcome.draft.currencyCode)
        assertEquals(RuleKind.WITHDRAWAL, outcome.draft.kind)
    }

    @Test
    fun `currency-after-amount rule matches an amount-then-symbol notification`() {
        val engine = RuleEngine(
            listOf(BuiltInRules.CURRENCY_AFTER_AMOUNT),
            currencySymbols = mapOf("€" to "EUR"),
            defaultAccountCurrency = "EUR",
        )
        val outcome = engine.match(message(text = "You paid 100.50€ at Shop1234")) as ParseOutcome.Matched

        assertEquals(BigDecimal("100.50"), outcome.draft.amount)
        assertEquals("EUR", outcome.draft.currencyCode)
    }

    @Test
    fun `currency-before-amount also handles a 3-letter ISO code instead of a symbol`() {
        val engine = RuleEngine(listOf(BuiltInRules.CURRENCY_BEFORE_AMOUNT), defaultAccountCurrency = "EUR")
        val outcome = engine.match(message(text = "You paid EUR 100.50 at Shop1234")) as ParseOutcome.Matched

        assertEquals(BigDecimal("100.50"), outcome.draft.amount)
    }

    @Test
    fun `a lone comma before a 3-letter word is not taken as the amount`() {
        val engine = RuleEngine(
            listOf(BuiltInRules.CURRENCY_AFTER_AMOUNT),
            currencySymbols = mapOf("€" to "EUR"),
            defaultAccountCurrency = "EUR",
        )
        val outcome = engine.match(
            message(
                title = "Payment at SHOPONE, LUX",
                text = "You paid 123,45 € with card •••• 0000 at SHOPONE, LUX.",
            ),
        ) as ParseOutcome.Matched

        assertEquals(BigDecimal("123.45"), outcome.draft.amount)
        assertEquals("EUR", outcome.draft.currencyCode)
    }

    @Test
    fun `a user rule with a digit-anchored amount skips a comma in the title`() {
        val rule = ParseRule(
            id = "user-rule",
            label = "User rule",
            kind = RuleKind.WITHDRAWAL,
            homeCurrency = "EUR",
            pattern = """(?<amount>\d[\d.,]*)\s*(?<currency>[A-Z]{1,3}\$|[A-Z]{3}|[€£$¥]).*?(?:at|bei)\s+(?<counterparty>.+?)\.?$""",
        )
        val engine = RuleEngine(listOf(rule), currencySymbols = mapOf("€" to "EUR"))
        val outcome = engine.match(
            message(
                title = "Payment at SHOPONE, LUX",
                text = "You paid 123,45 € with card •••• 0000 at SHOPONE, LUX.",
            ),
        ) as ParseOutcome.Matched

        assertEquals(BigDecimal("123.45"), outcome.draft.amount)
        assertEquals("EUR", outcome.draft.currencyCode)
    }

    @Test
    fun `description and notes come from the raw title and body, no counterparty capture`() {
        val engine = RuleEngine(listOf(BuiltInRules.CURRENCY_AFTER_AMOUNT), defaultAccountCurrency = "EUR")
        val outcome = engine.match(message(title = "Payment notice", text = "100.50 EUR at Shop1234")) as ParseOutcome.Matched

        assertEquals("Payment notice", outcome.draft.description)
        assertEquals("100.50 EUR at Shop1234", outcome.draft.notes)
    }

    @Test
    fun `withBuiltIns appends both rules after the given list when enabled`() {
        val userRule = BuiltInRules.CURRENCY_AFTER_AMOUNT.copy(id = "user-rule")
        val combined = listOf(userRule).withBuiltIns(builtInRulesEnabled = true)

        assertEquals(listOf(userRule, BuiltInRules.CURRENCY_BEFORE_AMOUNT, BuiltInRules.CURRENCY_AFTER_AMOUNT), combined)
    }

    @Test
    fun `withBuiltIns leaves the list untouched when disabled`() {
        val userRule = BuiltInRules.CURRENCY_AFTER_AMOUNT.copy(id = "user-rule")
        val combined = listOf(userRule).withBuiltIns(builtInRulesEnabled = false)

        assertEquals(listOf(userRule), combined)
    }

    @Test
    fun `a matching user rule still wins over the built-ins placed after it`() {
        val userRule = ParseRule(
            id = "user-rule",
            label = "User rule",
            kind = RuleKind.DEPOSIT,
            homeCurrency = "EUR",
            pattern = """received (?<amount>[\d.]+)""",
        )
        val engine = RuleEngine(listOf(userRule).withBuiltIns(true))
        val outcome = engine.match(message(text = "You received 100.50 from Shop1234")) as ParseOutcome.Matched

        assertEquals("user-rule", outcome.rule.id)
        assertFalse(outcome.rule.id in BuiltInRules.IDS)
    }

    @Test
    fun `both built-in ids are stable and distinct`() {
        assertEquals(2, BuiltInRules.IDS.size)
        assertTrue(BuiltInRules.CURRENCY_BEFORE_AMOUNT.id in BuiltInRules.IDS)
        assertTrue(BuiltInRules.CURRENCY_AFTER_AMOUNT.id in BuiltInRules.IDS)
    }
}
