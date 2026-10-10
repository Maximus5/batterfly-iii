package io.github.maximus5.ffnotifyimporter.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import io.github.maximus5.ffnotifyimporter.R

/** A "Save" text action in the settings toolbar, always visible - settings forms are long, so a
 * button at the bottom of the scroll view is easy to miss. Call from `onViewCreated`; the item
 * lives only while the fragment's view is resumed. */
fun Fragment.addSaveMenu(onSave: () -> Unit) {
    requireActivity().addMenuProvider(
        object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) = menuInflater.inflate(R.menu.menu_save, menu)

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (menuItem.itemId != R.id.action_save) return false
                onSave()
                return true
            }
        },
        viewLifecycleOwner,
        Lifecycle.State.RESUMED,
    )
}
