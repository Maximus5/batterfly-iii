package io.github.maximus5.ffnotifyimporter.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import io.github.maximus5.ffnotifyimporter.R

/**
 * The one "you have unsaved changes" confirmation every editing screen shows when the user leaves
 * with edits pending (the Server and Transactions settings, the rule editor, the transaction
 * create/edit screen).
 *
 * Both buttons are explicit actions - there is no "Cancel", which read as "cancel my changes" and
 * made the choice ambiguous. "Keep editing" (stay on the screen) is on the left and "Discard
 * changes" (leave and lose the edits) on the right: the user's own call, a deliberate exception to
 * the "Cancel is rightmost" rule in AGENTS.md, since here staying is the action people are after.
 * In `AlertDialog.Builder` terms the left slot is the negative button and the right one the
 * positive button.
 */
fun confirmDiscardChanges(context: Context, onDiscard: () -> Unit) {
    AlertDialog.Builder(context)
        .setTitle(R.string.discard_changes_title)
        .setMessage(R.string.discard_changes_message)
        .setNegativeButton(R.string.discard_changes_keep_editing, null)
        .setPositiveButton(R.string.discard_changes_discard) { _, _ -> onDiscard() }
        .show()
}
