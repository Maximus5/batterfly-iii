package io.github.maximus5.ffnotifyimporter.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class DateParserTest {

    @Test
    fun `parses a day-first two-digit-year date to noon UTC on that date`() {
        assertEquals(Instant.parse("2030-02-01T12:00:00Z"), parseDate("01.02.30", "dd.MM.yy"))
    }

    @Test
    fun `parses a four-digit-year date`() {
        assertEquals(Instant.parse("2030-02-01T12:00:00Z"), parseDate("01.02.2030", "dd.MM.yyyy"))
    }

    @Test
    fun `parses an ISO date`() {
        assertEquals(Instant.parse("2030-02-01T12:00:00Z"), parseDate("2030-02-01", "yyyy-MM-dd"))
    }

    @Test
    fun `trims surrounding whitespace before parsing`() {
        assertEquals(Instant.parse("2030-02-01T12:00:00Z"), parseDate("  01.02.30  ", "dd.MM.yy"))
    }

    @Test
    fun `returns null when the text does not match the format`() {
        assertNull(parseDate("01.02.30", "yyyy-MM-dd"))
    }

    @Test
    fun `returns null for a malformed pattern`() {
        // An unterminated quoted literal - DateTimeFormatter.ofPattern throws
        // IllegalArgumentException for this, a reliable way to hit the malformed-pattern path
        // without depending on which ASCII letters happen to be valid pattern symbols.
        assertNull(parseDate("01.02.30", "dd.MM.'yy"))
    }

    @Test
    fun `returns null for a date-only string missing a required field`() {
        assertNull(parseDate("01.02", "dd.MM.yyyy"))
    }
}
