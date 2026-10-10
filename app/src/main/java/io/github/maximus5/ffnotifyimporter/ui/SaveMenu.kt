package io.github.maximus5.ffnotifyimporter.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import io.github.maximus5.ffnotifyimporter.R

/** A filled "Save" button (and, when [onDelete] is given, a trash icon) in the settings toolbar,
 * always visible - settings forms are long, so a button at the bottom of the scroll view is easy
 * to miss. Call from `onViewCreated`; the items live only while the fragment's view is resumed. */
fun Fragment.addSaveMenu(onDelete: (() -> Unit)? = null, onSave: () -> Unit) {
    requireActivity().addMenuProvider(
        object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_save, menu)
                // An action-layout item never reaches onMenuItemSelected - its view takes the click.
                menu.findItem(R.id.action_save).actionView?.setOnClickListener { onSave() }
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
}
