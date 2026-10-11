package io.github.maximus5.ffnotifyimporter.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestionMatchesTest {

    private val all = listOf("Clothes", "Dark clothing", "Groceries", "Close friends", "Games")

    @Test
    fun `matches entries and words that start with the query, case-insensitively`() {
        assertEquals(listOf("Clothes", "Dark clothing", "Close friends"), suggestionMatches(all, "clo"))
    }

    @Test
    fun `does not match in the middle of a word`() {
        assertEquals(emptyList<String>(), suggestionMatches(all, "othes"))
    }

    @Test
    fun `a blank query matches nothing`() {
        assertEquals(emptyList<String>(), suggestionMatches(all, ""))
        assertEquals(emptyList<String>(), suggestionMatches(all, "   "))
    }

    @Test
    fun `excluded entries are not offered, compared case-insensitively`() {
        assertEquals(listOf("Dark clothing", "Close friends"), suggestionMatches(all, "clo", excluded = setOf("clothes")))
    }

    @Test
    fun `a query with surrounding spaces is trimmed`() {
        assertEquals(listOf("Groceries"), suggestionMatches(all, "  gro "))
    }

    @Test
    fun `words are split on any non-letter so hyphenated and accented names match`() {
        assertEquals(listOf("bank-import"), suggestionMatches(listOf("bank-import", "other"), "imp"))
        assertEquals(listOf("Café Latte"), suggestionMatches(listOf("Café Latte"), "lat"))
    }
}
