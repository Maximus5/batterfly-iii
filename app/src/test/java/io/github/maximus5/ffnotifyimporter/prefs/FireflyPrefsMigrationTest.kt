package io.github.maximus5.ffnotifyimporter.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FireflyPrefsMigrationTest {

    @Test
    fun `secrets and the cleartext flag never move out of the connection file`() {
        val old = mapOf<String, Any?>(
            "base_url" to "<encrypted>", "token" to "<encrypted>", "allow_cleartext" to true,
            "auto_create" to true, "dedup_window_minutes" to 7, "all_transactions_tag" to "a,b",
        )
        val moved = FireflyPrefs.settingsToMigrate(old)
        assertEquals(setOf("auto_create", "dedup_window_minutes", "all_transactions_tag"), moved.keys)
        assertEquals(7, moved["dedup_window_minutes"])
    }

    @Test
    fun `nothing to move once the old file holds only the connection`() {
        assertTrue(FireflyPrefs.settingsToMigrate(mapOf("base_url" to "x", "token" to "y")).isEmpty())
        assertTrue(FireflyPrefs.settingsToMigrate(emptyMap()).isEmpty())
    }

    @Test
    fun `migrated keys are exactly the nine plain settings`() {
        assertEquals(9, FireflyPrefs.MIGRATED_KEYS.size)
        for (secret in listOf("base_url", "token", "allow_cleartext")) {
            assertFalse(secret in FireflyPrefs.MIGRATED_KEYS)
        }
    }
}
