package io.github.maximus5.ffnotifyimporter.posting

import io.github.maximus5.ffnotifyimporter.network.FireflyCurrency
import io.github.maximus5.ffnotifyimporter.rules.CurrencyEntry
import io.github.maximus5.ffnotifyimporter.rules.TransactionDraft
import io.github.maximus5.ffnotifyimporter.rules.RuleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class CurrencyLearningTest {

    private fun draft(currency: String, foreign: String? = null) = TransactionDraft(
        kind = RuleKind.WITHDRAWAL,
        date = Instant.parse("2026-01-01T00:00:00Z"),
        amount = BigDecimal("1.00"),
        currencyCode = currency,
        foreignAmount = foreign?.let { BigDecimal("2.00") },
        foreignCurrencyCode = foreign,
        sourceName = null,
        sourceAccountId = null,
        destinationName = null,
        destinationAccountId = null,
        description = "Shop1234",
        notes = "",
        category = null,
        tags = emptyList(),
        issues = emptySet(),
    )

    private val firefly = listOf(
        FireflyCurrency("EUR", true, "Euro", "€"),
        FireflyCurrency("PLN", true, "Zloty", "zł"),
        FireflyCurrency("CHF", false, "Franc", "Fr."),
        FireflyCurrency("USD", true, "Dollar", "$"),
        FireflyCurrency("CAD", true, "Canadian dollar", "$"),
    )

    @Test
    fun `a symbol is unresolved but a 3-letter code is not`() {
        assertEquals(setOf("zł"), unresolvedCurrencySymbols(draft("zł")))
        assertEquals(emptySet<String>(), unresolvedCurrencySymbols(draft("EUR")))
    }

    @Test
    fun `both currency fields are checked and blank is ignored`() {
        assertEquals(setOf("zł", "Fr."), unresolvedCurrencySymbols(draft("zł", foreign = "Fr.")))
        assertEquals(emptySet<String>(), unresolvedCurrencySymbols(draft("")))
    }

    @Test
    fun `a symbol carried by exactly one enabled currency is learnable`() {
        assertEquals(CurrencyEntry("PLN", "zł"), learnableSymbolEntry("zł", firefly, emptyList()))
    }

    @Test
    fun `a disabled currency is not learned`() {
        assertNull(learnableSymbolEntry("Fr.", firefly, emptyList()))
    }

    @Test
    fun `a symbol shared by several enabled currencies is not guessed`() {
        assertNull(learnableSymbolEntry("$", firefly, emptyList()))
    }

    @Test
    fun `an unknown symbol is not learnable`() {
        assertNull(learnableSymbolEntry("₿", firefly, emptyList()))
    }

    @Test
    fun `an existing row for the code is never overwritten`() {
        assertNull(learnableSymbolEntry("zł", firefly, listOf(CurrencyEntry("PLN", "PLN"))))
    }
}
