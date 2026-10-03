package io.github.maximus5.ffnotifyimporter.rules

import org.junit.Assert.assertTrue
import org.junit.Test

class RuleValidationTest {

    private fun validRule() = ParseRule(
        id = "r1",
        label = "Test",
        kind = RuleKind.WITHDRAWAL,
        accountName = "Bank Account A",
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
        assertTrue(errors.any { it.contains("regex", ignoreCase = true) })
    }

    @Test
    fun `transfer rules need a counterparty account name`() {
        val errors = validateRule(validRule().copy(kind = RuleKind.TRANSFER_OUT, counterpartyAccountName = null))
        assertTrue(errors.any { it.contains("counterparty", ignoreCase = true) })
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
