package io.github.maximus5.ffnotifyimporter.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionSettingsTest {

    @Test
    fun `legacy 1+ days keeps persisting with the same window`() {
        assertEquals(RetentionSettings(persistToDisk = true, days = 1), RetentionSettings.fromLegacyDays(1))
        assertEquals(RetentionSettings(persistToDisk = true, days = 30), RetentionSettings.fromLegacyDays(30))
    }

    @Test
    fun `legacy 0 stays in memory only with the default window`() {
        assertEquals(RetentionSettings(persistToDisk = false, days = 5), RetentionSettings.fromLegacyDays(0))
    }

    @Test
    fun `an unsupported window falls back to the default`() {
        assertEquals(5, RetentionSettings.normalizeDays(0))
        assertEquals(5, RetentionSettings.normalizeDays(99))
        assertEquals(7, RetentionSettings.normalizeDays(7))
    }
}
