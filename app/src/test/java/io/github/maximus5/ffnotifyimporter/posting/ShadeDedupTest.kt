package io.github.maximus5.ffnotifyimporter.posting

import io.github.maximus5.ffnotifyimporter.rules.RuleKind
import io.github.maximus5.ffnotifyimporter.rules.TransactionDraft
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadeDedupTest {

    private fun draft(
        amount: BigDecimal? = BigDecimal("12.50"),
        description: String = "Shop1234",
        millis: Long = 1_000_000L,
    ) = TransactionDraft(
        kind = RuleKind.WITHDRAWAL,
        date = Instant.ofEpochMilli(millis),
        amount = amount,
        currencyCode = "EUR",
        foreignAmount = null,
        foreignCurrencyCode = null,
        sourceName = "Bank Account A",
        sourceAccountId = null,
        destinationName = null,
        destinationAccountId = null,
        description = description,
        notes = "",
        category = null,
        tags = emptyList(),
        issues = emptySet(),
    )

    @Test
    fun `a draft without a resolved amount has no shade entry`() {
        assertNull(shadeEntryFor(draft(amount = null)))
    }

    @Test
    fun `the same payment reported by two apps is a duplicate even if only the description differs, when descriptions are ignored`() {
        val first = shadeEntryFor(draft(description = "Shop1234"))!!
        val second = shadeEntryFor(draft(description = "SHOP1234 CITY"))!!

        assertTrue(isDuplicateInShade(listOf(first), second, includeDescription = false, windowMinutes = 5))
        assertFalse(isDuplicateInShade(listOf(first), second, includeDescription = true, windowMinutes = 5))
    }

    @Test
    fun `identical payments further apart than the window are not duplicates`() {
        val first = shadeEntryFor(draft(millis = 1_000_000L))!!
        val later = shadeEntryFor(draft(millis = 1_000_000L + 6 * 60_000L))!!

        assertFalse(isDuplicateInShade(listOf(first), later, includeDescription = true, windowMinutes = 5))
        assertTrue(isDuplicateInShade(listOf(first), later, includeDescription = true, windowMinutes = 10))
    }

    @Test
    fun `a different amount is not a duplicate`() {
        val first = shadeEntryFor(draft())!!
        val other = shadeEntryFor(draft(amount = BigDecimal("12.51")))!!

        assertFalse(isDuplicateInShade(listOf(first), other, includeDescription = false, windowMinutes = 5))
    }

    @Test
    fun `an empty shade never reports a duplicate`() {
        assertFalse(isDuplicateInShade(emptyList(), shadeEntryFor(draft())!!, includeDescription = true, windowMinutes = 5))
        assertNotNull(shadeEntryFor(draft()))
        assertEquals(1_000_000L, shadeEntryFor(draft())!!.dateMillis)
    }
}
