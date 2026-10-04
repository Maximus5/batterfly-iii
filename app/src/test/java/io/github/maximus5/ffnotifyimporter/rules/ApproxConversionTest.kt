package io.github.maximus5.ffnotifyimporter.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class ApproxConversionTest {

    private fun draft(
        amount: BigDecimal? = null,
        foreignAmount: BigDecimal? = BigDecimal("10.00"),
        foreignCurrencyCode: String? = "GBP",
        tags: List<String> = emptyList(),
        issues: Set<DraftIssue> = setOf(DraftIssue.LOCAL_AMOUNT_MISSING),
    ) = TransactionDraft(
        kind = RuleKind.WITHDRAWAL,
        date = Instant.ofEpochMilli(1000),
        amount = amount,
        currencyCode = "EUR",
        foreignAmount = foreignAmount,
        foreignCurrencyCode = foreignCurrencyCode,
        sourceName = "Bank Account A",
        destinationName = null,
        description = "Shop1234",
        notes = "",
        category = null,
        tags = tags,
        issues = issues,
    )

    @Test
    fun `computes amount from foreign amount times rate, rounded to 2 decimals`() {
        val updated = applyApproxConversion(draft(foreignAmount = BigDecimal("10.00")), BigDecimal("1.1543"), "")

        assertEquals(BigDecimal("11.54"), updated.amount)
    }

    @Test
    fun `half-up rounds the third decimal`() {
        val updated = applyApproxConversion(draft(foreignAmount = BigDecimal("1.00")), BigDecimal("1.005"), "")

        assertEquals(BigDecimal("1.01"), updated.amount)
    }

    @Test
    fun `removes the LOCAL_AMOUNT_MISSING issue once an amount is computed`() {
        val updated = applyApproxConversion(draft(), BigDecimal("1.10"), "")

        assertTrue(updated.issues.isEmpty())
    }

    @Test
    fun `appends the approx-conversion tag when one is configured`() {
        val updated = applyApproxConversion(draft(tags = listOf("groceries")), BigDecimal("1.10"), "fx-approx")

        assertEquals(listOf("groceries", "fx-approx"), updated.tags)
    }

    @Test
    fun `does not duplicate the approx tag if already present`() {
        val updated = applyApproxConversion(draft(tags = listOf("fx-approx")), BigDecimal("1.10"), "fx-approx")

        assertEquals(listOf("fx-approx"), updated.tags)
    }

    @Test
    fun `a blank approx tag adds nothing`() {
        val updated = applyApproxConversion(draft(tags = listOf("groceries")), BigDecimal("1.10"), "")

        assertEquals(listOf("groceries"), updated.tags)
    }

    @Test
    fun `a draft with no foreign amount is returned unchanged`() {
        val original = draft(foreignAmount = null, foreignCurrencyCode = null)
        val updated = applyApproxConversion(original, BigDecimal("1.10"), "fx-approx")

        assertEquals(original, updated)
        assertFalse(updated.tags.contains("fx-approx"))
    }
}
