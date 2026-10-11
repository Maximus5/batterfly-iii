package io.github.maximus5.ffnotifyimporter.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportFileNameTest {
    @Test
    fun slugLowercasesAndJoinsWordsWithDashes() {
        assertEquals("butterfly-iii", fileNameSlug("Butterfly III"))
    }

    @Test
    fun slugCollapsesPunctuationAndTrimsEdges() {
        assertEquals("my-app-2", fileNameSlug("  My App: 2! "))
    }
}
