package io.github.maximus5.ffnotifyimporter.parser

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Parses a date-only [raw] string (no time of day - banks announcing a future charge date never
 * give a time, just a day) against a user-specified [pattern] (a `DateTimeFormatter` pattern,
 * e.g. `"dd.MM.yy"` - see [io.github.maximus5.ffnotifyimporter.rules.ParseRule.dateFormat]'s KDoc
 * for why this isn't auto-detected). Null if [pattern] itself is malformed or [raw] doesn't match
 * it - the caller ([io.github.maximus5.ffnotifyimporter.rules.RuleEngine]) is responsible for
 * flagging that rather than silently using a wrong date.
 *
 * Resolves to noon UTC on the parsed date, not midnight - an arbitrary but safe fixed time that
 * avoids any timezone-driven day-rollover surprises later (midnight UTC can display as the
 * *previous* day in a timezone behind UTC), since the bank never actually told us a time anyway.
 */
fun parseDate(raw: String, pattern: String): Instant? {
    val formatter = try {
        DateTimeFormatter.ofPattern(pattern)
    } catch (e: IllegalArgumentException) {
        return null
    }
    return try {
        LocalDate.parse(raw.trim(), formatter).atTime(12, 0).toInstant(ZoneOffset.UTC)
    } catch (e: DateTimeParseException) {
        null
    }
}
