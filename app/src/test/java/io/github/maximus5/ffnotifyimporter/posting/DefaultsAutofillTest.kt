package io.github.maximus5.ffnotifyimporter.posting

import io.github.maximus5.ffnotifyimporter.network.FireflyAccount
import io.github.maximus5.ffnotifyimporter.network.FireflyCurrency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultsAutofillTest {

    private val currencies = listOf(
        FireflyCurrency("USD", enabled = true),
        FireflyCurrency("EUR", enabled = true, primary = true),
        FireflyCurrency("PLN", enabled = false),
    )

    @Test
    fun `the primary enabled currency is chosen`() {
        assertEquals("EUR", chooseDefaults(currencies, null).currency)
    }

    @Test
    fun `a primary currency that is disabled is not chosen`() {
        assertNull(chooseDefaults(listOf(FireflyCurrency("EUR", enabled = false, primary = true)), null).currency)
    }

    @Test
    fun `no primary currency means no guess`() {
        assertNull(chooseDefaults(listOf(FireflyCurrency("USD", enabled = true)), null).currency)
    }

    @Test
    fun `a single asset account becomes the default source account`() {
        assertEquals("Account1", chooseDefaults(null, listOf(FireflyAccount("1", "Account1"))).sourceAccount)
    }

    @Test
    fun `several asset accounts are not guessed`() {
        val accounts = listOf(FireflyAccount("1", "Account1"), FireflyAccount("2", "Account2"))
        assertNull(chooseDefaults(null, accounts).sourceAccount)
    }

    @Test
    fun `no accounts and failed lookups give nothing`() {
        assertNull(chooseDefaults(null, emptyList()).sourceAccount)
        assertEquals(DefaultsAutofill(), chooseDefaults(null, null))
    }
}
