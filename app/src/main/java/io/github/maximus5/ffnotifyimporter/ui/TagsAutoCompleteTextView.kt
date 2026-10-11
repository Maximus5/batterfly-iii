package io.github.maximus5.ffnotifyimporter.ui

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.textfield.MaterialAutoCompleteTextView

/**
 * A comma-separated list field that suggests matches for the tag being typed (the text after the
 * last comma), like `MultiAutoCompleteTextView` with a comma tokenizer - but built on
 * [MaterialAutoCompleteTextView], so it gets the same outlined-box look inside a `TextInputLayout`
 * as every other field on the screen (the framework's multi variant does not).
 */
class TagsAutoCompleteTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.autoCompleteTextViewStyle,
) : MaterialAutoCompleteTextView(context, attrs, defStyleAttr) {

    /** `[start, end)` of the tag at the cursor: after the previous comma (and its spaces) up to the cursor. */
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

    override fun enoughToFilter(): Boolean = currentToken().length >= threshold

    override fun performFiltering(text: CharSequence?, keyCode: Int) {
        super.performFiltering(currentToken(), keyCode)
    }

    override fun replaceText(text: CharSequence) {
        val editable = getText() ?: return
        val start = tokenStart()
        val end = selectionEnd.coerceIn(start, editable.length)
        editable.replace(start, end, "$text, ")
    }
}
