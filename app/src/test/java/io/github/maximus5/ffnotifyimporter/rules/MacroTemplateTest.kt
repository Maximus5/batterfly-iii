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
}
