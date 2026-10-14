package io.github.maximus5.ffnotifyimporter.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the Auto Backup allowlists (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`): the
 * sensitive stores must never be included, the two files must agree, and every database /
 * SharedPreferences file the app defines must be consciously classified as included or excluded.
 */
class BackupRulesTest {

    private val resXml = File("src/main/res/xml")
    private val includeRegex = Regex("""<include\s+domain="(database|sharedpref)"\s+path="([^"]+)"""")

    private fun includes(file: String, section: String? = null): Set<String> {
        var text = File(resXml, file).readText()
        if (section != null) text = text.substringAfter("<$section>").substringBefore("</$section>")
        return includeRegex.findAll(text).map { it.groupValues[2] }.toSet()
    }

    @Test
    fun `legacy and Android 12 rules list the same files, for cloud backup and device transfer`() {
        val legacy = includes("backup_rules.xml")
        assertTrue(legacy.isNotEmpty())
        assertEquals(legacy, includes("data_extraction_rules.xml", "cloud-backup"))
        assertEquals(legacy, includes("data_extraction_rules.xml", "device-transfer"))
    }

    @Test
    fun `sensitive stores are never backed up`() {
        val included = includes("backup_rules.xml") + includes("data_extraction_rules.xml")
        for (name in SENSITIVE) {
            assertFalse("$name must not be in the backup rules", included.any { it.startsWith(name) })
        }
    }

    @Test
    fun `the secure settings file is explicitly excluded in every rule set`() {
        val exclude = Regex("""<exclude\s+domain="sharedpref"\s+path="firefly_connection\.xml"""")
        val legacy = File(resXml, "backup_rules.xml").readText()
        val modern = File(resXml, "data_extraction_rules.xml").readText()
        assertEquals(1, exclude.findAll(legacy).count())
        assertEquals(1, exclude.findAll(modern.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")).count())
        assertEquals(1, exclude.findAll(modern.substringAfter("<device-transfer>").substringBefore("</device-transfer>")).count())
    }

    @Test
    fun `backup code never touches the secure settings`() {
        val comments = Regex("""/\*.*?\*/|//[^\n]*""", RegexOption.DOT_MATCHES_ALL)
        val forbidden = Regex("""SecureSettings|KeystoreCipher|\.(getToken|setToken|getBaseUrl|setBaseUrl|apiClient|isAllowCleartext|setAllowCleartext)""")
        val offenders = File("src/main/java").walkTopDown()
            .filter { it.extension == "kt" && it.path.replace('\\', '/').contains("/backup/") }
            .filter { forbidden.containsMatchIn(it.readText().replace(comments, "")) }
            .map { it.name }.toList()
        assertTrue("backup/ must not read the secure settings: $offenders", offenders.isEmpty())
    }

    @Test
    fun `every database and prefs file in the code is classified`() {
        val declared = Regex("""(?:DB_NAME|PREFS_NAME)\s*=\s*"([^"]+)"|getSharedPreferences\("([^"]+)"""")
        val found = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> declared.findAll(f.readText()).map { it.groupValues[1].ifEmpty { it.groupValues[2] } } }
            .toSet()
        assertTrue("expected to find storage names in the sources", found.size >= 8)
        val included = includes("backup_rules.xml").map { it.removeSuffix(".xml") }.toSet()
        val unclassified = found - included - EXCLUDED.map { it.removeSuffix(".xml") }.toSet()
        assertTrue("Decide backup vs. exclude for: $unclassified (edit both XML files / EXCLUDED)", unclassified.isEmpty())
    }

    private companion object {
        val SENSITIVE = listOf("message_history.db", "created_transactions.db", "firefly_connection", "diagnostic_log")
        val EXCLUDED = SENSITIVE + listOf("currencies.db", "startup_migrations")
    }
}
