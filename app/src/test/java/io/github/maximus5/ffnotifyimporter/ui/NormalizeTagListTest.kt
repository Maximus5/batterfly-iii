package io.github.maximus5.ffnotifyimporter.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizeTagListTest {
    @Test fun `drops the trailing comma and space a picked suggestion leaves`() =
        assertEquals("Tag-One", normalizeTagList("Tag-One, "))

    @Test fun `drops leading and doubled separators and trims each tag`() =
        assertEquals("a, b, c", normalizeTagList(" , a ,, b,c ,  "))

    @Test fun `keeps a single tag and a macro template as they are`() {
        assertEquals("fx-approx", normalizeTagList("fx-approx"))
        assertEquals("{merchant}, bank", normalizeTagList("{merchant},bank"))
    }

    @Test fun `blank and comma-only input become empty`() {
        assertEquals("", normalizeTagList(""))
        assertEquals("", normalizeTagList(" , , "))
    }
}
