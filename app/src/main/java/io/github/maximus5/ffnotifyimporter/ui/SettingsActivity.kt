package io.github.maximus5.ffnotifyimporter.ui

import android.content.Context
import android.content.Intent
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
            val direct = intent.getStringExtra(EXTRA_SCREEN)?.let(SetupScreen::fromName)
            if (direct != null) {
                // No back-stack entry: back/up leaves Settings and lands on whoever launched it
                // (the Messages setup checklist), not on the Settings list.
                setScreenTitle(direct.titleRes)
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, direct.newFragment())
                    .commit()
            } else {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, SettingsRootFragment())
                    .commit()
            }
        }
    }

    /** Pushes [fragment] on top of the current screen, titled [titleRes], with a back-stack entry. */
    fun openScreen(fragment: Fragment, titleRes: Int) {
        setScreenTitle(titleRes)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    /** Re-asserts [titleRes] as the toolbar title - [openScreen] already does this once at push
     * time, but that alone isn't enough: popping back to a screen (root or otherwise) doesn't
     * re-run it, which used to leave whatever title the screen being left behind had (tracked via
     * `addOnBackStackChangedListener` resetting to "Settings" only when the back stack emptied
     * out completely - missed the Rules-list case one level down, and was one more moving part
     * than needed). Each settings fragment calls this itself from its own `onResume`, so the
     * title is always correct for whichever fragment is actually visible, regardless of how it
     * got there (forward navigation, back, or an activity recreation restoring it from the back
     * stack without going through [openScreen] at all). */
    fun setScreenTitle(titleRes: Int) {
        supportActionBar?.title = getString(titleRes)
    }

    /** `RulesListFragment`'s multi-select (long-press to select, move/enable/disable/delete from
     * the toolbar - see its own KDoc) - the "N selected" title while it's active, same
     * `messages_selected_count` string `MainActivity.updateSelectionCount` already uses for the
     * same purpose on the Messages list. A plain formatted string, not a `titleRes`, unlike
     * [setScreenTitle] - this is the one title here that isn't a fixed per-screen resource.
     * [setBackGuard] (already existing) doubles as this mode's "cancel selection" hook - no
     * separate mechanism needed the way `MainActivity` has its own `onSelectionCancel`, since this
     * activity already routes both the toolbar's up arrow and system back through one guard. */
    fun updateSelectionCount(count: Int) {
        supportActionBar?.title = getString(R.string.messages_selected_count, count)
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

    /** Closes the current screen: pops it if it was pushed on the back stack, otherwise (opened
     * directly as the root, e.g. from the Messages setup checklist) finishes the activity. Plain
     * `popBackStack()` does nothing in the second case, which is why screens use this instead. */
    fun leaveScreen() = defaultBack()

    private fun defaultBack() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            finish()
        }
    }

    /** Settings screens the Messages setup checklist can open directly, as the activity's root. */
    enum class SetupScreen(val titleRes: Int, val newFragment: () -> Fragment) {
        NOTIFICATION_ACCESS(R.string.notification_access_consent_title, ::NotificationAccessConsentFragment),
        FIREFLY(R.string.settings_firefly_title, ::FireflyConnectionFragment),
        APPS(R.string.settings_apps_screen_title, ::AppsFragment),
        TRANSACTIONS(R.string.settings_transaction_creation_title, ::TransactionCreationFragment),
        NOTIFICATIONS(R.string.settings_notifications_title, ::NotificationSettingsFragment),
        ADD_TEST_MESSAGE(R.string.settings_add_test_message_title, ::AddTestMessageFragment),
        ;

        companion object {
            fun fromName(name: String): SetupScreen? = entries.firstOrNull { it.name == name }
        }
    }

    companion object {
        private const val EXTRA_SCREEN = "screen"

        fun intentFor(context: Context, screen: SetupScreen): Intent =
            Intent(context, SettingsActivity::class.java).putExtra(EXTRA_SCREEN, screen.name)
    }
}
