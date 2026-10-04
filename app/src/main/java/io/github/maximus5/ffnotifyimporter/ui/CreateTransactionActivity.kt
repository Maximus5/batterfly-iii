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
 * test-match dialog for a *fresh* match (nothing linked yet); the already-linked
 * (Open/Create Again) and no-match cases stay as that dialog, unchanged - there's nothing to
 * review/edit in either of those.
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

        /** [message] is passed as its own plain fields, not a `Parcelable`/serialized
         * `CapturedMessage` - [CreateTransactionFragment] re-parses it fresh via `RuleEngine.match`
         * the same way `posting.AutoCreateWorker` reconstructs one from `WorkManager.Data`, rather
         * than trying to carry a `TransactionDraft` (`BigDecimal`/`Instant`/`List<String>`) across
         * process boundaries. Only ever launched for a message with no `fireflyTransactionId` yet -
         * see this class's own KDoc. */
        fun intent(context: Context, message: CapturedMessage): Intent =
            Intent(context, CreateTransactionActivity::class.java)
                .putExtra(EXTRA_PACKAGE_NAME, message.packageName)
                .putExtra(EXTRA_APP_LABEL, message.appLabel)
                .putExtra(EXTRA_TITLE, message.title)
                .putExtra(EXTRA_TEXT, message.text)
                .putExtra(EXTRA_TIMESTAMP_MILLIS, message.timestampMillis)

        fun messageFrom(intent: Intent): CapturedMessage =
            CapturedMessage(
                packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty(),
                appLabel = intent.getStringExtra(EXTRA_APP_LABEL).orEmpty(),
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                text = intent.getStringExtra(EXTRA_TEXT).orEmpty(),
                timestampMillis = intent.getLongExtra(EXTRA_TIMESTAMP_MILLIS, 0L),
            )
    }
}
