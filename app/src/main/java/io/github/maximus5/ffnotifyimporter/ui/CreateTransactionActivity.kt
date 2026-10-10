package io.github.maximus5.ffnotifyimporter.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.databinding.ActivityCreateTransactionBinding

/**
 * Hosts [CreateTransactionFragment] - a single screen, not a multi-level area like
 * `SettingsActivity`, so this skips that class's `openScreen`/back-stack machinery and just shows
 * the one fragment directly. The pre-create review/edit screen the user asked for (see TODO.md's
 * dedup history) - replaces what used to be `MessagesFragment.showTestMatch`'s read-only
 * test-match dialog for a *fresh* match. The no-match case stays as that dialog, unchanged -
 * there's nothing to review/edit there. The already-linked case (Open/Create Again) still shows
 * that dialog first too, as a read-only summary, but "Create Again" itself now opens this screen
 * as well, same as every other manual create path - the user's own explicit rule: bypassing this
 * review screen is only ever allowed for `posting.AutoCreateWorker`'s background
 * auto-create-by-notification path, never for a manual action, including a manual "create anyway"
 * on an already-confirmed duplicate.
 *
 * **Fixed a real "pointless extra step" complaint, the user's own catch**: "Create Again" used to
 * show its own confirmation dialog (`MessagesFragment.confirmCreateAgain`, "this will post a
 * second transaction, are you sure?") *before* ever reaching this screen - redundant with the
 * screen's own on-submit duplicate check, which finds the exact same already-linked transaction
 * again and asks the real "are you sure" at the point that actually matters (about to post), via
 * [CreateTransactionFragment.confirmCreateAnyway]. That dialog is gone now; "Create Again" opens
 * this screen directly, which shows an explicit warning banner instead (see
 * [CreateTransactionFragment] for why) - informational while editing, not a gate, since the real
 * gate already exists further down the same flow.
 */
class CreateTransactionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityCreateTransactionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        useClassicSystemBars(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, CreateTransactionFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private const val EXTRA_PACKAGE_NAME = "package_name"
        private const val EXTRA_APP_LABEL = "app_label"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_TIMESTAMP_MILLIS = "timestamp_millis"
        private const val EXTRA_EXISTING_TRANSACTION_ID = "existing_transaction_id"

        /** [message] is passed as its own plain fields, not a `Parcelable`/serialized
         * `CapturedMessage` - [CreateTransactionFragment] re-parses it fresh via `RuleEngine.match`
         * the same way `posting.AutoCreateWorker` reconstructs one from `WorkManager.Data`, rather
         * than trying to carry a `TransactionDraft` (`BigDecimal`/`Instant`/`List<String>`) across
         * process boundaries. [message] may already carry a `fireflyTransactionId` (a "Create
         * Again" re-launch - always a *verified* one by the time this is reachable, see
         * `MessagesFragment.showTestMatch`/`verifyLinkedThenShow`) - now carried across as its own
         * extra too, the user's own request: [CreateTransactionFragment] shows an explicit warning
         * banner when it's present, rather than the separate confirmation dialog this used to need
         * before ever reaching this screen. The fragment doesn't otherwise treat it specially - it
         * just re-matches and posts, overwriting the link with whatever gets created this time,
         * same "latest wins" reasoning `MessagesFragment` always used for that case. */
        fun intent(context: Context, message: CapturedMessage): Intent =
            Intent(context, CreateTransactionActivity::class.java)
                .putExtra(EXTRA_PACKAGE_NAME, message.packageName)
                .putExtra(EXTRA_APP_LABEL, message.appLabel)
                .putExtra(EXTRA_TITLE, message.title)
                .putExtra(EXTRA_TEXT, message.text)
                .putExtra(EXTRA_TIMESTAMP_MILLIS, message.timestampMillis)
                .putExtra(EXTRA_EXISTING_TRANSACTION_ID, message.fireflyTransactionId)

        fun messageFrom(intent: Intent): CapturedMessage =
            CapturedMessage(
                packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty(),
                appLabel = intent.getStringExtra(EXTRA_APP_LABEL).orEmpty(),
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                text = intent.getStringExtra(EXTRA_TEXT).orEmpty(),
                timestampMillis = intent.getLongExtra(EXTRA_TIMESTAMP_MILLIS, 0L),
                fireflyTransactionId = intent.getStringExtra(EXTRA_EXISTING_TRANSACTION_ID),
            )
    }
}
