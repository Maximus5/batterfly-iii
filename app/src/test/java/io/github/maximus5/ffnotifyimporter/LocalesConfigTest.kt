package io.github.maximus5.ffnotifyimporter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The system "App language" page only offers what `locales_config.xml` lists, so it must match the
 * translations the app actually ships: the default `values/` (English) plus every `values-xx/`. */
class LocalesConfigTest {

    @Test
    fun `locales_config lists English plus every translated values folder`() {
        val res = File("src/main/res")
        val translated = res.listFiles { f -> f.isDirectory && f.name.matches(Regex("values-[a-z]{2}")) && File(f, "strings.xml").exists() }
            .orEmpty().map { it.name.removePrefix("values-") }.toSet()
        val declared = Regex("""<locale\s+android:name="([^"]+)"""")
            .findAll(File(res, "xml/locales_config.xml").readText()).map { it.groupValues[1] }.toSet()

        assertTrue(translated.isNotEmpty())
        assertEquals(translated + "en", declared)
    }

    @Test
    fun `manifest points at locales_config`() {
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("""android:localeConfig="@xml/locales_config""""))
    }
}
