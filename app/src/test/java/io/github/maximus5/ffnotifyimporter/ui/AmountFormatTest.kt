package io.github.maximus5.ffnotifyimporter.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class AmountFormatTest {

    @Test
    fun `strips the 12-digit scale Firefly returns`() {
        assertEquals("44.86", formatAmountForDisplay(BigDecimal("44.860000000000")))
    }

    @Test
    fun `whole amounts keep two decimals`() {
        assertEquals("100.00", formatAmountForDisplay(BigDecimal("100.000000000000")))
        assertEquals("1000.00", formatAmountForDisplay(BigDecimal("1E+3")))
    }

    @Test
    fun `a genuine third decimal is kept`() {
        assertEquals("1.235", formatAmountForDisplay(BigDecimal("1.235000000000")))
    }
}
