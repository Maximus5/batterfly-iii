package io.github.maximus5.ffnotifyimporter.ui

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.ActivitySettingsBinding

/**
 * Root of the settings area: a list of config sections (monitored apps, message history, and
 * - later - Firefly III connection, processing rules, appearance). Each section is its own
 * fragment pushed onto this activity's back stack, so "up"/back always lands back on the list.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** Set by the current fragment (via [setBackGuard]) to intercept *both* the toolbar's up
     * arrow and system back (button/gesture) with its own logic instead of the default
     * pop-or-finish - e.g. [RuleEditFragment] uses this to confirm before discarding unsaved
     * edits. Same two-trigger-one-handler shape as `MainActivity`'s `onSelectionCancel`, just
     * hosted here since this is where the editor fragment lives. */
    private var backGuard: (() -> Unit)? = null

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            backGuard?.invoke() ?: defaultBack()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        useClassicSystemBars(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, SettingsRootFragment())
                .commit()
        }

        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                supportActionBar?.title = getString(R.string.action_settings)
            }
        }
    }

    /** Pushes [fragment] on top of the current screen, titled [titleRes], with a back-stack entry. */
    fun openScreen(fragment: Fragment, titleRes: Int) {
        supportActionBar?.title = getString(titleRes)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    /** [guard] is called instead of the default back behavior for as long as it's set - passing
     * null restores the default. The caller decides what "back" means while it's registered
     * (e.g. show a confirm dialog, then actually leave only once the user agrees). */
    fun setBackGuard(guard: (() -> Unit)?) {
        backGuard = guard
        backPressedCallback.isEnabled = guard != null
    }

    override fun onSupportNavigateUp(): Boolean {
        backGuard?.invoke() ?: defaultBack()
        return true
    }

    private fun defaultBack() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            finish()
        }
    }
}
