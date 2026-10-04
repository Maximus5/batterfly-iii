package io.github.maximus5.ffnotifyimporter.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    private fun candidate(
        amount: BigDecimal = BigDecimal("12.50"),
        currencyCode: String = "EUR",
        description: String = "Shop1234",
        sourceId: String? = null,
        sourceName: String? = "Bank Account A",
        destinationId: String? = null,
        destinationName: String? = null,
        date: Instant = Instant.ofEpochMilli(1000),
    ) = RemoteTransactionCandidate(
        groupId = "99",
        date = date,
        amount = amount,
        currencyCode = currencyCode,
        description = description,
        sourceId = sourceId,
        sourceName = sourceName,
        destinationId = destinationId,
        destinationName = destinationName,
    )

    @Test
    fun `remoteCandidateMatches is true for the same payment within the window`() {
        assertTrue(remoteCandidateMatches(draft(), candidate(), true, 5))
    }

    @Test
    fun `remoteCandidateMatches is false when the amount differs`() {
        assertFalse(remoteCandidateMatches(draft(), candidate(amount = BigDecimal("12.51")), true, 5))
    }

    @Test
    fun `remoteCandidateMatches is false outside the window even if everything else matches`() {
        val sixMinutesLater = Instant.ofEpochMilli(1000 + 6 * 60_000L)
        assertFalse(remoteCandidateMatches(draft(), candidate(date = sixMinutesLater), true, 5))
    }

    @Test
    fun `remoteCandidateMatches is true at the exact window edge`() {
        val fiveMinutesLater = Instant.ofEpochMilli(1000 + 5 * 60_000L)
        assertTrue(remoteCandidateMatches(draft(), candidate(date = fiveMinutesLater), true, 5))
    }

    @Test
    fun `remoteCandidateMatches only compares description when includeDescription is true`() {
        val differentDescription = candidate(description = "Rewe")

        assertTrue(remoteCandidateMatches(draft(), differentDescription, false, 5))
        assertFalse(remoteCandidateMatches(draft(), differentDescription, true, 5))
    }

    @Test
    fun `remoteCandidateMatches ignores the candidate's real Firefly id for a side the draft only knows by name`() {
        // Firefly assigns every account (even an expense/revenue counterparty) a real id, but this
        // app's draft only ever has one for the asset side of a rule - the counterparty here
        // (destination) must still match by name, not get rejected just because the draft has no
        // id to compare the candidate's destinationId against.
        val sameCounterpartyByName = candidate(destinationId = "42", destinationName = "Shop1234")
        val d = draft(destinationAccountId = null, destinationName = "Shop1234")

        assertTrue(remoteCandidateMatches(d, sameCounterpartyByName, true, 5))
    }

    @Test
    fun `remoteCandidateMatches is false for a different counterparty even with the same amount and window`() {
        val differentCounterparty = candidate(destinationId = "42", destinationName = "Rewe")
        val d = draft(destinationAccountId = null, destinationName = "Shop1234")

        assertFalse(remoteCandidateMatches(d, differentCounterparty, true, 5))
    }

    @Test
    fun `remoteCandidateMatches compares the asset side by id when the draft has one`() {
        val sameAccountById = candidate(sourceId = "2", sourceName = "Bank Account A (renamed since)")
        val d = draft(sourceAccountId = "2", sourceName = "Bank Account A")

        assertTrue(remoteCandidateMatches(d, sameAccountById, true, 5))
    }
}
