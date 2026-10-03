package io.github.maximus5.ffnotifyimporter.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class AmountParserTest {

    @Test
    fun `comma decimal separator, dot thousands separator`() {
        assertEquals(BigDecimal("1234.56"), parseAmount("1.234,56", ','))
    }

    @Test
    fun `dot decimal separator, comma thousands separator`() {
        assertEquals(BigDecimal("1234.56"), parseAmount("1,234.56", '.'))
    }

    @Test
    fun `sign is ignored - direction comes from the rule`() {
        assertEquals(BigDecimal("123.45"), parseAmount("-123,45", ','))
    }

    @Test
    fun `more than one decimal separator is unparsable`() {
        assertNull(parseAmount("45,30,56", ','))
    }

    @Test
    fun `zero is not a valid amount`() {
        assertNull(parseAmount("0,00", ','))
    }

    @Test
    fun `no digits is unparsable`() {
        assertNull(parseAmount("EUR", ','))
    }
}
