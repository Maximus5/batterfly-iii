package io.github.maximus5.ffnotifyimporter.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.databinding.ActivityMainBinding

/** Home screen: just the captured-messages list. Everything else lives behind the settings icon. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var onSelectionCopy: (() -> Unit)? = null
    private var onSelectionDelete: (() -> Unit)? = null
    private var onSelectionCancel: (() -> Unit)? = null

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

        // Gates the very first screen the user sees on notification-listener access, not just
        // a banner buried in Settings -> Monitored apps - the user's own call: the app does
        // nothing useful without this permission, so the consent screen belongs front and
        // center on a fresh install, not something to stumble onto. Every launch, not just a
        // true first run (no separate "has seen onboarding" flag) - the permission's own granted
        // state already is that signal, and if it's still missing on a later launch, showing the
        // disclosure again is more helpful than a dead-looking empty Messages list.
        if (savedInstanceState == null) {
            val initial = if (isNotificationListenerAccessGranted(this)) MessagesFragment() else NotificationAccessConsentFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, initial)
                .commit()
        }
    }

    /** [NotificationAccessConsentFragment]'s own dismiss - called only when it's sitting as this
     * activity's *root* fragment (no back stack to pop instead, see that class's own KDoc), i.e.
     * only ever right after the `savedInstanceState == null` branch above chose it over
     * `MessagesFragment`. Swaps it out the same way, just later. */
    fun showMessages() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, MessagesFragment())
            .commit()
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
            R.id.action_copy_selected -> {
                onSelectionCopy?.invoke()
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

    /** Standard `Intent` launch mode recreates this activity (`onCreate`) rather than reusing an
     * existing instance for most real-world launches of [checkMessageIntent]'s `FLAG_ACTIVITY_
     * CLEAR_TOP`, so [MessagesFragment.consumePendingMessageCheck] firing from its own
     * `onViewCreated` already covers that common case - this is the fallback for whichever launch
     * mode/OEM combination instead reuses a still-running instance and delivers here instead. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (supportFragmentManager.findFragmentById(R.id.fragmentContainer) as? MessagesFragment)?.consumePendingMessageCheck()
    }

    /**
     * Swaps the toolbar into "N selected" + copy/delete actions, replacing its normal title/menu in
     * place - the *same* single top bar, not a second one stacked above it. `startSupportActionMode`
     * (what this replaces) rendered its contextual bar as an extra bar above this activity's own
     * custom Toolbar instead of replacing it - a known AppCompat quirk with a manually-set,
     * NoActionBar-theme Toolbar - which also pushed/obscured the first list item underneath both
     * bars. A hand-rolled toggle of this one Toolbar's content has no such ambiguity.
     */
    fun enterSelectionMode(count: Int, onCopy: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
        onSelectionCopy = onCopy
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
        onSelectionCopy = null
        onSelectionDelete = null
        onSelectionCancel = null
        backPressedCallback.isEnabled = false
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.title = getString(R.string.app_name)
        invalidateOptionsMenu()
    }

    companion object {
        private const val EXTRA_CHECK_PACKAGE_NAME = "check_message_package_name"
        private const val EXTRA_CHECK_APP_LABEL = "check_message_app_label"
        private const val EXTRA_CHECK_TITLE = "check_message_title"
        private const val EXTRA_CHECK_TEXT = "check_message_text"
        private const val EXTRA_CHECK_TIMESTAMP_MILLIS = "check_message_timestamp_millis"

        /** `ReviewNotifications`'s "Create transaction" action - the user's own request: run the
         * exact same preemptive dedup check a normal tap on the message's own row runs
         * (`MessagesFragment.showTestMatch`/`checkThenOpenCreateOrShowFound`), rather than jumping
         * straight into `CreateTransactionActivity` with no check at all (the original design,
         * documented in `ReviewNotifications`'s own history) - a likely duplicate now surfaces the
         * same "Use Created"/"Open"/"Create anyway"/"Cancel" dialog a list tap would, instead of
         * silently walking into a blank create screen that only finds out it's a duplicate if/when
         * Firefly's own server-side hash check rejects the post. Opens [MainActivity] rather than
         * running the check from inside a notification action's own `PendingIntent` receiver -
         * `AlertDialog`s need a foreground `Activity`, and `MessagesFragment` already owns every
         * piece of UI this check can show. [MessagesFragment.consumeCheckMessageIntent] is the
         * other half - reads these extras back out (and clears them) once the fragment's view is
         * ready to act on them. */
        fun checkMessageIntent(context: Context, message: CapturedMessage): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CHECK_PACKAGE_NAME, message.packageName)
                putExtra(EXTRA_CHECK_APP_LABEL, message.appLabel)
                putExtra(EXTRA_CHECK_TITLE, message.title)
                putExtra(EXTRA_CHECK_TEXT, message.text)
                putExtra(EXTRA_CHECK_TIMESTAMP_MILLIS, message.timestampMillis)
            }

        /** Reconstructs the message [checkMessageIntent] carried (same plain-fields shape
         * `CreateTransactionActivity.messageFrom` uses, for the same "don't serialize a
         * `CapturedMessage` across an Activity boundary, there's nothing to serialize" reason -
         * not that there would be here anyway, `CapturedMessage` has no exotic field types) and
         * clears those extras from [intent] in place - without that, rotating the screen or coming
         * back from a dialog's "Open" button (which backgrounds this activity to show Firefly in a
         * browser) would re-run the same check again on every recreation/resume, popping the dialog
         * a second time unprompted. Null when [intent] carries none of this - the normal case, a
         * plain app launch or any other notification's own tap target. */
        fun consumeCheckMessageIntent(intent: Intent): CapturedMessage? {
            val packageName = intent.getStringExtra(EXTRA_CHECK_PACKAGE_NAME) ?: return null
            val message = CapturedMessage(
                packageName = packageName,
                appLabel = intent.getStringExtra(EXTRA_CHECK_APP_LABEL).orEmpty(),
                title = intent.getStringExtra(EXTRA_CHECK_TITLE).orEmpty(),
                text = intent.getStringExtra(EXTRA_CHECK_TEXT).orEmpty(),
                timestampMillis = intent.getLongExtra(EXTRA_CHECK_TIMESTAMP_MILLIS, 0L),
            )
            intent.removeExtra(EXTRA_CHECK_PACKAGE_NAME)
            intent.removeExtra(EXTRA_CHECK_APP_LABEL)
            intent.removeExtra(EXTRA_CHECK_TITLE)
            intent.removeExtra(EXTRA_CHECK_TEXT)
            intent.removeExtra(EXTRA_CHECK_TIMESTAMP_MILLIS)
            return message
        }
    }
}
