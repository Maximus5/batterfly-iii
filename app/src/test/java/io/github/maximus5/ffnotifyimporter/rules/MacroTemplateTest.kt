package io.github.maximus5.ffnotifyimporter.rules

import org.junit.Assert.assertEquals
import org.junit.Test

class MacroTemplateTest {

    @Test
    fun `substitutes title, body and captured groups`() {
        val result = renderTemplate(
            template = "{counterparty} - {title} ({amount} {currency})",
            groups = mapOf("counterparty" to "Shop1234", "amount" to "12,50", "currency" to "EUR"),
            title = "Payment",
            body = "You paid 12,50 EUR",
        )
        assertEquals("Shop1234 - Payment (12,50 EUR)", result)
    }

    @Test
    fun `unknown macro is left as literal text`() {
        val result = renderTemplate("{nope}", groups = emptyMap(), title = "t", body = "b")
        assertEquals("{nope}", result)
    }

    @Test
    fun `templateMacroNames lists every macro referenced`() {
        assertEquals(setOf("counterparty", "title"), templateMacroNames("{counterparty} wrote: {title}"))
    }

    @Test
    fun `escaped doubled braces render to the literal single-brace text, not macro-resolved`() {
        val result = renderTemplate("{{User}}", groups = emptyMap(), title = "t", body = "b")
        assertEquals("{User}", result)
    }

    @Test
    fun `escaped braces stay literal even when the name collides with a real declared group`() {
        val result = renderTemplate("{{counterparty}}", groups = mapOf("counterparty" to "Shop1234"), title = "t", body = "b")
        assertEquals("{counterparty}", result)
    }

    @Test
    fun `escaped braces stay literal even when the name collides with a fixed macro`() {
        val result = renderTemplate("{{title}}", groups = emptyMap(), title = "Payment", body = "b")
        assertEquals("{title}", result)
    }

    @Test
    fun `plain and escaped macros can appear in the same template`() {
        val result = renderTemplate(
            template = "{counterparty} tagged {{User}}",
            groups = mapOf("counterparty" to "Shop1234"),
            title = "t",
            body = "b",
        )
        assertEquals("Shop1234 tagged {User}", result)
    }

    @Test
    fun `templateMacroNames does not count an escaped pair as a macro reference`() {
        assertEquals(setOf("counterparty"), templateMacroNames("{counterparty} tagged {{User}}"))
    }

    @Test
    fun `templateMacroNames is empty for a template that only has escaped braces`() {
        assertEquals(emptySet<String>(), templateMacroNames("{{User}}"))
    }
}
