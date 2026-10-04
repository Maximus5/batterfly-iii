package io.github.maximus5.ffnotifyimporter.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.ActivityMainBinding
import io.github.maximus5.ffnotifyimporter.posting.isNotificationPermissionGranted
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs

/** Home screen: just the captured-messages list. Everything else lives behind the settings icon. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var onSelectionDelete: (() -> Unit)? = null
    private var onSelectionCancel: (() -> Unit)? = null

    /** Catches the case `FireflySettingsFragment`'s own request (when the switch is actually
     * toggled on) can't: auto-create already enabled from before this permission check existed,
     * or the permission later revoked by hand in system settings, with the user never opening
     * Firefly III settings again to re-trigger it there. Checked on every app start, not just
     * first install, for the same "could have changed since last time" reason. */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    // Starts disabled (falls through to the default back behavior - exit) and is only enabled
    // while a selection is active, so system back (button or gesture, not just the toolbar's own
    // up arrow) cancels the selection instead of closing the app.
    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            onSelectionCancel?.invoke()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        useClassicSystemBars(binding.root)

        setSupportActionBar(binding.toolbar)
        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, MessagesFragment())
                .commit()
        }

        if (FireflyPrefs(this).isAutoCreateEnabled() && !isNotificationPermissionGranted(this)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(if (onSelectionDelete != null) R.menu.menu_selection else R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_delete_selected -> {
                onSelectionDelete?.invoke()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onSelectionCancel?.invoke()
        return true
    }

    /**
     * Swaps the toolbar into "N selected" + a delete action, replacing its normal title/menu in
     * place - the *same* single top bar, not a second one stacked above it. `startSupportActionMode`
     * (what this replaces) rendered its contextual bar as an extra bar above this activity's own
     * custom Toolbar instead of replacing it - a known AppCompat quirk with a manually-set,
     * NoActionBar-theme Toolbar - which also pushed/obscured the first list item underneath both
     * bars. A hand-rolled toggle of this one Toolbar's content has no such ambiguity.
     */
    fun enterSelectionMode(count: Int, onDelete: () -> Unit, onCancel: () -> Unit) {
        onSelectionDelete = onDelete
        onSelectionCancel = onCancel
        backPressedCallback.isEnabled = true
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        updateSelectionCount(count)
        invalidateOptionsMenu()
    }

    fun updateSelectionCount(count: Int) {
        supportActionBar?.title = getString(R.string.messages_selected_count, count)
    }

    fun exitSelectionMode() {
        onSelectionDelete = null
        onSelectionCancel = null
        backPressedCallback.isEnabled = false
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.title = getString(R.string.app_name)
        invalidateOptionsMenu()
    }
}
