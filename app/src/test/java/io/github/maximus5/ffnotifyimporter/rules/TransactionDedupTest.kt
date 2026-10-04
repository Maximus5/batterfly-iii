package io.github.maximus5.ffnotifyimporter.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class TransactionDedupTest {

    private fun draft(
        amount: BigDecimal? = BigDecimal("12.50"),
        currencyCode: String = "EUR",
        sourceName: String? = "Bank Account A",
        sourceAccountId: String? = null,
        destinationName: String? = null,
        destinationAccountId: String? = null,
        description: String = "Shop1234",
    ) = TransactionDraft(
        kind = RuleKind.WITHDRAWAL,
        date = Instant.ofEpochMilli(1000),
        amount = amount,
        currencyCode = currencyCode,
        foreignAmount = null,
        foreignCurrencyCode = null,
        sourceName = sourceName,
        sourceAccountId = sourceAccountId,
        destinationName = destinationName,
        destinationAccountId = destinationAccountId,
        description = description,
        notes = "",
        category = null,
        tags = emptyList(),
        issues = emptySet(),
    )

    @Test
    fun `same draft hashes the same every time`() {
        assertEquals(transactionDedupHash(draft(), true), transactionDedupHash(draft(), true))
    }

    @Test
    fun `a different amount changes the hash`() {
        assertNotEquals(
            transactionDedupHash(draft(amount = BigDecimal("12.50")), true),
            transactionDedupHash(draft(amount = BigDecimal("12.51")), true),
        )
    }

    @Test
    fun `an amount differing only by scale hashes the same`() {
        assertEquals(
            transactionDedupHash(draft(amount = BigDecimal("12.5")), true),
            transactionDedupHash(draft(amount = BigDecimal("12.50")), true),
        )
    }

    @Test
    fun `account id is preferred over account name when both differ`() {
        val byName = draft(sourceAccountId = null, sourceName = "Bank Account A")
        val byId = draft(sourceAccountId = "42", sourceName = "Bank Account A (renamed)")

        assertNotEquals(transactionDedupHash(byName, true), transactionDedupHash(byId, true))
    }

    @Test
    fun `description only affects the hash when includeDescription is true`() {
        val a = draft(description = "Shop1234")
        val b = draft(description = "Rewe")

        assertEquals(transactionDedupHash(a, false), transactionDedupHash(b, false))
        assertNotEquals(transactionDedupHash(a, true), transactionDedupHash(b, true))
    }

    @Test
    fun `a different currency changes the hash even with the same amount`() {
        assertNotEquals(
            transactionDedupHash(draft(currencyCode = "EUR"), true),
            transactionDedupHash(draft(currencyCode = "CHF"), true),
        )
    }
}
