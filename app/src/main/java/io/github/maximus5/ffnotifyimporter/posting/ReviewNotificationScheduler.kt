package io.github.maximus5.ffnotifyimporter.posting

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs

/**
 * Entry point for the "review" notification - the counterpart to [AutoCreateScheduler] for when
 * auto-create is *off*. Called from the same two places right after a genuinely new message is
 * recorded (`notifications.BankNotificationListenerService`, `ui.AddTestMessageFragment`),
 * alongside (not instead of) [AutoCreateScheduler.scheduleIfEnabled] - exactly one of the two ever
 * does anything for a given message, since they gate on opposite states of
 * [FireflyPrefs.isAutoCreateEnabled].
 *
 * No unique work name/ordering constraint, unlike [AutoCreateScheduler] - there is no shared
 * mutable state ([ReviewNotificationWorker] only parses and notifies, never posts to Firefly or
 * writes the dedup log), so two of these running concurrently for two notifications that arrived
 * together is harmless.
 */
object ReviewNotificationScheduler {

    fun scheduleIfNeeded(context: Context, message: CapturedMessage) {
        val prefs = FireflyPrefs(context)
        if (prefs.isAutoCreateEnabled() || !prefs.isConfigured()) return

        val request = OneTimeWorkRequestBuilder<ReviewNotificationWorker>()
            .setInputData(ReviewNotificationWorker.inputData(message))
            .build()

        WorkManager.getInstance(context).enqueue(request)
    }
}
