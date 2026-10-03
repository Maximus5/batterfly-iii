package io.github.maximus5.ffnotifyimporter.rules

import org.junit.Assert.assertTrue
import org.junit.Test

class RuleValidationTest {

    private fun validRule() = ParseRule(
        id = "r1",
        label = "Test",
        packageNames = setOf("bank.app"),
        kind = RuleKind.WITHDRAWAL,
        sourceAccountName = "Bank Account A",
        homeCurrency = "EUR",
        pattern = """spent (?<amount>[\d,]+) EUR at (?<counterparty>.+)""",
        descriptionTemplate = "{counterparty}",
        notesTemplate = "{body}",
    )

    @Test
    fun `a well-formed rule has no errors`() {
        assertTrue(validateRule(validRule()).isEmpty())
    }

    @Test
    fun `pattern must define the amount group`() {
        val errors = validateRule(validRule().copy(pattern = """spent (?<notAmount>[\d,]+)"""))
        assertTrue(errors.any { it.contains("amount") })
    }

    @Test
    fun `invalid regex is reported`() {
        val errors = validateRule(validRule().copy(pattern = "("))
        assertTrue(errors.any { it.contains("regular expression", ignoreCase = true) })
    }

    @Test
    fun `withdrawal rules need a source account`() {
        val errors = validateRule(validRule().copy(sourceAccountName = ""))
        assertTrue(errors.any { it.contains("source", ignoreCase = true) })
    }

    @Test
    fun `deposit rules need a destination account`() {
        val errors = validateRule(
            validRule().copy(kind = RuleKind.DEPOSIT, sourceAccountName = "", destinationAccountName = ""),
        )
        assertTrue(errors.any { it.contains("destination", ignoreCase = true) })
    }

    @Test
    fun `transfer rules need both a source and a destination account`() {
        val errors = validateRule(
            validRule().copy(kind = RuleKind.TRANSFER, sourceAccountName = "", destinationAccountName = ""),
        )
        assertTrue(errors.any { it.contains("source", ignoreCase = true) })
        assertTrue(errors.any { it.contains("destination", ignoreCase = true) })
    }

    @Test
    fun `a macro template counts as set for the source-destination requirement`() {
        val errors = validateRule(validRule().copy(sourceAccountName = "{counterparty}"))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `unknown macro in source or destination account is reported`() {
        val errors = validateRule(validRule().copy(sourceAccountName = "{doesNotExist}"))
        assertTrue(errors.any { it.contains("doesNotExist") })
    }

    @Test
    fun `no apps selected is reported`() {
        val errors = validateRule(validRule().copy(packageNames = emptySet()))
        assertTrue(errors.any { it.contains("app", ignoreCase = true) })
    }

    @Test
    fun `unknown macro in a template is reported`() {
        val errors = validateRule(validRule().copy(descriptionTemplate = "{doesNotExist}"))
        assertTrue(errors.any { it.contains("doesNotExist") })
    }

    @Test
    fun `title and body macros are always valid even if not declared groups`() {
        val errors = validateRule(validRule().copy(descriptionTemplate = "{title}: {body}"))
        assertTrue(errors.isEmpty())
    }
}
