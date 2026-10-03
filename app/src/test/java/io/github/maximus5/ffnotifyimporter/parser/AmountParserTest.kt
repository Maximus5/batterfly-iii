package io.github.maximus5.ffnotifyimporter.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class AmountParserTest {

    @Test
    fun `european format - comma decimal, dot thousands`() {
        assertEquals(BigDecimal("1234.56"), parseAmount("1.234,56"))
    }

    @Test
    fun `us format - dot decimal, comma thousands`() {
        assertEquals(BigDecimal("1234.56"), parseAmount("1,234.56"))
    }

    @Test
    fun `swiss format - dot decimal, apostrophe thousands`() {
        assertEquals(BigDecimal("1234.56"), parseAmount("1'234.56"))
    }

    @Test
    fun `plain cents with comma`() {
        assertEquals(BigDecimal("12.50"), parseAmount("12,50"))
    }

    @Test
    fun `plain cents with dot`() {
        assertEquals(BigDecimal("12.50"), parseAmount("12.50"))
    }

    @Test
    fun `a separator followed by three digits is thousands grouping, not decimal`() {
        assertEquals(BigDecimal("1234"), parseAmount("1,234"))
    }

    @Test
    fun `whole number with no separator at all`() {
        assertEquals(BigDecimal("100"), parseAmount("100"))
    }

    @Test
    fun `sign is ignored - direction comes from the rule`() {
        assertEquals(BigDecimal("123.45"), parseAmount("-123,45"))
    }

    @Test
    fun `zero is not a valid amount`() {
        assertNull(parseAmount("0,00"))
    }

    @Test
    fun `no digits is unparsable`() {
        assertNull(parseAmount("EUR"))
    }

    @Test
    fun `leading decimal separator with no integer part defaults the integer part to zero`() {
        assertEquals(BigDecimal("0.50"), parseAmount(",50"))
    }
}
