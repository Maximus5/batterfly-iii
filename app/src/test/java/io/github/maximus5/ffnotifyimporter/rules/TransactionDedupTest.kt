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
        foreignAmount: BigDecimal? = null,
        foreignCurrencyCode: String? = null,
        sourceName: String? = "Bank Account A",
        sourceAccountId: String? = null,
        destinationName: String? = null,
        destinationAccountId: String? = null,
        description: String = "Shop1234",
        kind: RuleKind = RuleKind.WITHDRAWAL,
    ) = TransactionDraft(
        kind = kind,
        date = Instant.ofEpochMilli(1000),
        amount = amount,
        currencyCode = currencyCode,
        foreignAmount = foreignAmount,
        foreignCurrencyCode = foreignCurrencyCode,
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
    fun `account id is ignored by the hash - only name matters, even when an id is set`() {
        // The real cross-rule dedup bug this fixed: two different rules for the same real
        // account don't necessarily agree on whether a Firefly account id got cached for it
        // (one picked via the account picker, another free-typed) - hashing by id-or-name used
        // to make those two hash differently for the identical real account. Same name, different
        // (or absent) id must hash the same; different name must still hash differently.
        val noId = draft(sourceAccountId = null, sourceName = "Bank Account A")
        val withId = draft(sourceAccountId = "42", sourceName = "Bank Account A")
        val differentIdSameName = draft(sourceAccountId = "99", sourceName = "Bank Account A")
        val differentName = draft(sourceAccountId = "42", sourceName = "Bank Account A (renamed)")

        assertEquals(transactionDedupHash(noId, true), transactionDedupHash(withId, true))
        assertEquals(transactionDedupHash(withId, true), transactionDedupHash(differentIdSameName, true))
        assertNotEquals(transactionDedupHash(noId, true), transactionDedupHash(differentName, true))
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

    @Test
    fun `destination does not affect a WITHDRAWAL's hash - only source is required for that kind`() {
        val a = draft(kind = RuleKind.WITHDRAWAL, destinationName = "Shop1234")
        val b = draft(kind = RuleKind.WITHDRAWAL, destinationName = "Rewe")

        assertEquals(transactionDedupHash(a, true), transactionDedupHash(b, true))
    }

    @Test
    fun `source does not affect a DEPOSIT's hash - only destination is required for that kind`() {
        val a = draft(kind = RuleKind.DEPOSIT, sourceName = "Employer A")
        val b = draft(kind = RuleKind.DEPOSIT, sourceName = "Employer B")

        assertEquals(transactionDedupHash(a, true), transactionDedupHash(b, true))
    }

    @Test
    fun `both sides affect a TRANSFER's hash`() {
        val base = draft(kind = RuleKind.TRANSFER, sourceName = "Checking", destinationName = "Savings")
        val differentSource = draft(kind = RuleKind.TRANSFER, sourceName = "Other", destinationName = "Savings")
        val differentDestination = draft(kind = RuleKind.TRANSFER, sourceName = "Checking", destinationName = "Other")

        assertNotEquals(transactionDedupHash(base, true), transactionDedupHash(differentSource, true))
        assertNotEquals(transactionDedupHash(base, true), transactionDedupHash(differentDestination, true))
    }

    // Defaults deliberately mirror real Firefly API shape, not this app's own - Firefly assigns
    // every account a real id and a real (never null) name, including an unspecified counterparty
    // ("(no name)", confirmed on a real device) - unlike a TransactionDraft, which can genuinely
    // have nulls for a side its own rule never tracks. A test that doesn't override these should
    // still look like a real response, not an unrealistic shape that happens to pass by accident.
    private fun candidate(
        amount: BigDecimal = BigDecimal("12.50"),
        currencyCode: String = "EUR",
        foreignAmount: BigDecimal? = null,
        foreignCurrencyCode: String? = null,
        description: String = "Shop1234",
        sourceId: String? = "1",
        sourceName: String? = "Bank Account A",
        destinationId: String? = "2",
        destinationName: String? = "(no name)",
        date: Instant = Instant.ofEpochMilli(1000),
    ) = RemoteTransactionCandidate(
        groupId = "99",
        date = date,
        amount = amount,
        currencyCode = currencyCode,
        foreignAmount = foreignAmount,
        foreignCurrencyCode = foreignCurrencyCode,
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

    // --- Account-side comparison, per kind - only the side(s) RuleValidation.validateRule
    // actually requires a name for that kind participate at all (see transactionDedupHash):
    // WITHDRAWAL = source only, DEPOSIT = destination only, TRANSFER = both. ---

    @Test
    fun `remoteCandidateMatches WITHDRAWAL matches the source by name when the draft has no id`() {
        val sameSourceByName = candidate(sourceId = "42", sourceName = "Bank Account A")
        val d = draft(kind = RuleKind.WITHDRAWAL, sourceAccountId = null, sourceName = "Bank Account A")

        assertTrue(remoteCandidateMatches(d, sameSourceByName, true, 5))
    }

    @Test
    fun `remoteCandidateMatches WITHDRAWAL is false when the source differs`() {
        val differentSource = candidate(sourceId = "42", sourceName = "A Different Account")
        val d = draft(kind = RuleKind.WITHDRAWAL, sourceAccountId = null, sourceName = "Bank Account A")

        assertFalse(remoteCandidateMatches(d, differentSource, true, 5))
    }

    @Test
    fun `remoteCandidateMatches WITHDRAWAL ignores a matching id when the name differs - name only, no id fallback`() {
        val sameIdDifferentName = candidate(sourceId = "2", sourceName = "Bank Account A (renamed since)")
        val d = draft(kind = RuleKind.WITHDRAWAL, sourceAccountId = "2", sourceName = "Bank Account A")

        assertFalse(remoteCandidateMatches(d, sameIdDifferentName, true, 5))
    }

    @Test
    fun `remoteCandidateMatches WITHDRAWAL ignores the destination entirely, even a real name Firefly stores there`() {
        // A real on-device miss: a WITHDRAWAL rule only ever requires a source account name
        // (validateRule), so destinationName is legitimately null - but Firefly itself always
        // stores something there (here, a placeholder like "(no name)" for an unnamed
        // cash/expense account), never null. Destination isn't part of a WITHDRAWAL's hash at
        // all (see transactionDedupHash), so this can't block an otherwise-exact match.
        val realDestinationAtFirefly = candidate(destinationId = "23", destinationName = "(no name)")
        val d = draft(kind = RuleKind.WITHDRAWAL, destinationAccountId = null, destinationName = null)

        assertTrue(remoteCandidateMatches(d, realDestinationAtFirefly, true, 5))
    }

    @Test
    fun `remoteCandidateMatches DEPOSIT matches the destination by name when the draft has no id`() {
        val sameDestinationByName = candidate(destinationId = "42", destinationName = "Checking")
        val d = draft(kind = RuleKind.DEPOSIT, sourceName = null, destinationAccountId = null, destinationName = "Checking")

        assertTrue(remoteCandidateMatches(d, sameDestinationByName, true, 5))
    }

    @Test
    fun `remoteCandidateMatches DEPOSIT is false when the destination differs`() {
        val differentDestination = candidate(destinationId = "42", destinationName = "A Different Account")
        val d = draft(kind = RuleKind.DEPOSIT, sourceName = null, destinationAccountId = null, destinationName = "Checking")

        assertFalse(remoteCandidateMatches(d, differentDestination, true, 5))
    }

    @Test
    fun `remoteCandidateMatches DEPOSIT ignores a matching id when the name differs - name only, no id fallback`() {
        val sameIdDifferentName = candidate(destinationId = "2", destinationName = "Checking (renamed since)")
        val d = draft(kind = RuleKind.DEPOSIT, sourceName = null, destinationAccountId = "2", destinationName = "Checking")

        assertFalse(remoteCandidateMatches(d, sameIdDifferentName, true, 5))
    }

    @Test
    fun `remoteCandidateMatches DEPOSIT ignores the source entirely, even a real name Firefly stores there`() {
        val realSourceAtFirefly = candidate(sourceId = "23", sourceName = "Employer Inc", destinationName = "Checking")
        val d = draft(kind = RuleKind.DEPOSIT, sourceName = null, destinationAccountId = null, destinationName = "Checking")

        assertTrue(remoteCandidateMatches(d, realSourceAtFirefly, true, 5))
    }

    @Test
    fun `remoteCandidateMatches TRANSFER requires both sides to match`() {
        val d = draft(
            kind = RuleKind.TRANSFER,
            sourceAccountId = "2",
            sourceName = "Checking",
            destinationAccountId = "7",
            destinationName = "Savings",
        )

        val sameBoth = candidate(sourceId = "2", sourceName = "Checking", destinationId = "7", destinationName = "Savings")
        assertTrue(remoteCandidateMatches(d, sameBoth, true, 5))

        val differentSource = candidate(sourceId = "9", sourceName = "Other", destinationId = "7", destinationName = "Savings")
        assertFalse(remoteCandidateMatches(d, differentSource, true, 5))

        val differentDestination = candidate(sourceId = "2", sourceName = "Checking", destinationId = "9", destinationName = "Other")
        assertFalse(remoteCandidateMatches(d, differentDestination, true, 5))
    }

    // --- Foreign-currency matching - a real on-device miss: the draft's own home-currency
    // amount is only this app's approximate conversion when a foreign amount/currency is set, not
    // something Firefly's stored amount is likely to match exactly, so the foreign side is
    // compared instead whenever the draft has one (see transactionDedupHash/remoteCandidateMatches
    // KDoc). ---

    @Test
    fun `remoteCandidateMatches compares by foreign amount-currency when the draft has one, ignoring a mismatched home amount`() {
        val d = draft(amount = BigDecimal("123.45"), currencyCode = "EUR", foreignAmount = BigDecimal("987.65"), foreignCurrencyCode = "HKD")
        // Firefly's own stored home-currency amount differs from this app's own approx conversion -
        // must not matter, since the foreign amount/currency is what's actually compared.
        val c = candidate(amount = BigDecimal("123.00"), currencyCode = "EUR", foreignAmount = BigDecimal("987.65"), foreignCurrencyCode = "HKD")

        assertTrue(remoteCandidateMatches(d, c, true, 5))
    }

    @Test
    fun `remoteCandidateMatches is false when the foreign currency differs`() {
        val d = draft(foreignAmount = BigDecimal("987.65"), foreignCurrencyCode = "HKD")
        val c = candidate(foreignAmount = BigDecimal("987.65"), foreignCurrencyCode = "USD")

        assertFalse(remoteCandidateMatches(d, c, true, 5))
    }

    @Test
    fun `remoteCandidateMatches is false when the foreign amount differs`() {
        val d = draft(foreignAmount = BigDecimal("987.65"), foreignCurrencyCode = "HKD")
        val c = candidate(foreignAmount = BigDecimal("987.66"), foreignCurrencyCode = "HKD")

        assertFalse(remoteCandidateMatches(d, c, true, 5))
    }

    @Test
    fun `remoteCandidateMatches is false when the draft has a foreign amount but the candidate has none`() {
        val d = draft(foreignAmount = BigDecimal("987.65"), foreignCurrencyCode = "HKD")
        val c = candidate(foreignAmount = null, foreignCurrencyCode = null)

        assertFalse(remoteCandidateMatches(d, c, true, 5))
    }
}
