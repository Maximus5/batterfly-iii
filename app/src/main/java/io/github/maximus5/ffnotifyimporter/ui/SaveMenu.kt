package io.github.maximus5.ffnotifyimporter.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Button
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import io.github.maximus5.ffnotifyimporter.R

/** Live handle to the toolbar button - [label] and [enabled] survive the menu being re-created. */
class SaveMenuHandle internal constructor() {
    private var button: Button? = null

    var label: CharSequence? = null
        set(value) {
            field = value
            apply()
        }

    var enabled: Boolean = true
        set(value) {
            field = value
            apply()
        }

    internal fun attach(button: Button?) {
        this.button = button
        apply()
    }

    private fun apply() {
        val button = button ?: return
        label?.let { button.text = it }
        button.isEnabled = enabled
    }
}

/** A filled "Save" button (and, when [onDelete] is given, a trash icon) in the toolbar,
 * always visible - forms are long, so a button at the bottom of the scroll view is easy
 * to miss. Call from `onViewCreated`; the items live only while the fragment's view is resumed. */
fun Fragment.addSaveMenu(onDelete: (() -> Unit)? = null, onSave: () -> Unit): SaveMenuHandle {
    val handle = SaveMenuHandle()
    requireActivity().addMenuProvider(
        object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_save, menu)
                // An action-layout item never reaches onMenuItemSelected - its view takes the click.
                val button = menu.findItem(R.id.action_save).actionView as? Button
                button?.setOnClickListener { onSave() }
                handle.attach(button)
                if (onDelete == null) menu.removeItem(R.id.action_delete)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (menuItem.itemId != R.id.action_delete) return false
                onDelete?.invoke()
                return true
            }
        },
        viewLifecycleOwner,
        Lifecycle.State.RESUMED,
    )
    return handle
}
