package io.github.maximus5.ffnotifyimporter.ui

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.textfield.MaterialAutoCompleteTextView

/**
 * A comma-separated list field that suggests matches for the tag being typed (the text after the
 * last comma), like `MultiAutoCompleteTextView` with a comma tokenizer - but built on
 * [MaterialAutoCompleteTextView], so it gets the same outlined-box look inside a `TextInputLayout`
 * as every other field on the screen (the framework's multi variant does not). Tags already in the
 * field are not offered again. Works as a single-tag field too (no commas typed).
 */
class TagsAutoCompleteTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.autoCompleteTextViewStyle,
) : MaterialAutoCompleteTextView(context, attrs, defStyleAttr) {

    private var suggestionAdapter: SuggestionAdapter? = null

    /** Sets the tag names offered; call again when a fresher list arrives. */
    fun setSuggestions(tags: List<String>) {
        val adapter = SuggestionAdapter(context, tags)
        suggestionAdapter = adapter
        setAdapter(adapter)
    }

    /** Start of the tag at the cursor: after the previous comma (and its spaces). */
    private fun tokenStart(): Int {
        val text = text?.toString().orEmpty()
        val cursor = selectionEnd.coerceIn(0, text.length)
        var start = text.lastIndexOf(',', cursor - 1) + 1
        while (start < cursor && text[start] == ' ') start++
        return start
    }

    private fun currentToken(): String {
        val text = text?.toString().orEmpty()
        val cursor = selectionEnd.coerceIn(0, text.length)
        return text.substring(tokenStart().coerceAtMost(cursor), cursor)
    }

    /** The other tags in the field (lower-case), so they are not suggested a second time. */
    private fun otherTags(): Set<String> {
        val text = text?.toString().orEmpty()
        val cursor = selectionEnd.coerceIn(0, text.length)
        val currentIndex = text.substring(0, cursor).count { it == ',' }
        return text.split(',').filterIndexed { i, _ -> i != currentIndex }
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    override fun enoughToFilter(): Boolean = currentToken().length >= threshold

    override fun performFiltering(text: CharSequence?, keyCode: Int) {
        suggestionAdapter?.excluded = otherTags()
        super.performFiltering(currentToken(), keyCode)
    }

    override fun replaceText(text: CharSequence) {
        val editable = getText() ?: return
        val start = tokenStart()
        val end = selectionEnd.coerceIn(start, editable.length)
        editable.replace(start, end, "$text, ")
    }
}
