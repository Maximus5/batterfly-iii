package io.github.maximus5.ffnotifyimporter.ui

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter

private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}]+")

/**
 * The entries of [all] that match what is being typed: the entry itself, or any word of it, starts
 * with [query] (case-insensitive) - so "clo" finds "Clothes" and "Dark clothing". Entries in
 * [excluded] (lower-case, e.g. the tags already in the field) are left out. A blank query matches
 * nothing. Pure, so it is unit-tested.
 */
fun suggestionMatches(all: List<String>, query: String, excluded: Set<String> = emptySet()): List<String> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return all.filter { item ->
        val lower = item.lowercase()
        lower !in excluded && (lower.startsWith(q) || lower.split(WORD_SPLIT).any { it.startsWith(q) })
    }
}

/** Drop-down adapter for the category and tag fields, filtered by [suggestionMatches]. */
class SuggestionAdapter(context: Context, private val all: List<String>) :
    ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line, ArrayList()) {

    /** Lower-case entries not to offer (set by the tags field before each filtering). */
    var excluded: Set<String> = emptySet()

    private val suggestionFilter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val matches = suggestionMatches(all, constraint?.toString().orEmpty(), excluded)
            return FilterResults().apply {
                values = matches
                count = matches.size
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults) {
            clear()
            addAll(results.values as List<String>)
            if (results.count > 0) notifyDataSetChanged() else notifyDataSetInvalidated()
        }
    }

    override fun getFilter(): Filter = suggestionFilter
}
