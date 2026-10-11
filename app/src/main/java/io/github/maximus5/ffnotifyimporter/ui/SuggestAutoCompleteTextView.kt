package io.github.maximus5.ffnotifyimporter.ui

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.textfield.MaterialAutoCompleteTextView

/** How long typing has to pause before the suggestion list is shown or refreshed. */
private const val SUGGESTION_DELAY_MILLIS = 300L

/**
 * [MaterialAutoCompleteTextView] that waits [SUGGESTION_DELAY_MILLIS] after the last keystroke
 * before showing suggestions, so the list does not flash up (and, for a one-letter query, a long
 * one) while the user is still typing. The list is (re)filtered once typing has paused, and only
 * if the field still has focus; a list that is already open stays until then.
 */
open class SuggestAutoCompleteTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.autoCompleteTextViewStyle,
) : MaterialAutoCompleteTextView(context, attrs, defStyleAttr) {

    private var pendingFilter: Runnable? = null

    final override fun performFiltering(text: CharSequence?, keyCode: Int) {
        pendingFilter?.let { removeCallbacks(it) }
        // Not dismissDropDown() here: it also stops the popup from being updated by the result of
        // this very filtering, so nothing would show up after the pause.
        val filter = Runnable {
            pendingFilter = null
            if (isAttachedToWindow && hasFocus()) filterNow(text, keyCode)
        }
        pendingFilter = filter
        postDelayed(filter, SUGGESTION_DELAY_MILLIS)
    }

    /** The actual filtering, run once typing has paused; subclasses may change what is filtered by. */
    protected open fun filterNow(text: CharSequence?, keyCode: Int) {
        super.performFiltering(text, keyCode)
    }

    override fun onDetachedFromWindow() {
        pendingFilter?.let { removeCallbacks(it) }
        pendingFilter = null
        super.onDetachedFromWindow()
    }
}
