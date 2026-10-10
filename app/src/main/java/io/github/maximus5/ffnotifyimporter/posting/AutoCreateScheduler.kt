package io.github.maximus5.ffnotifyimporter.posting

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import java.util.concurrent.TimeUnit

/**
 * Entry point for automatic transaction creation - called right after a genuinely new message is
 * recorded, from both real-notification capture (`notifications.BankNotificationListenerService`)
 * and the manual debug injector (`ui.AddTestMessageFragment` - "exactly like a real notification
 * would", per its own KDoc, includes this). A no-op unless [FireflyPrefs.isAutoCreateEnabled].
 *
 * Every message is enqueued into the *same* WorkManager unique work chain
 * ([UNIQUE_WORK_NAME], [ExistingWorkPolicy.APPEND_OR_REPLACE]) regardless of origin - the user's
 * own explicit requirement: two notifications can arrive essentially simultaneously, and
 * auto-create must never run two attempts concurrently (a race between two duplicate checks could
 * let both decide "not a duplicate" and both post the same real payment). Appending makes each
 * new request a dependency of whatever is already queued/running in that chain, so WorkManager
 * itself guarantees strict one-at-a-time, in-arrival-order execution - no manual locking needed,
 * and it survives process death (WorkManager persists its queue), so a burst that arrives right
 * before the app gets killed still finishes in order once it restarts.
 *
 * **Fixed a real "every future message silently never runs" bug, root-caused on the user's own
 * device by reading `androidx.work.workdb` directly**: plain [ExistingWorkPolicy.APPEND] makes a
 * new request a dependent of the chain's current *tail*, whatever state it's in - and WorkManager
 * cascades a `FAILED` (or `CANCELLED`) tail's terminal state to every dependent without ever
 * running them. A tail *did* reach `FAILED` on the user's device, from `AutoCreateWorker.post`'s
 * now-removed retry-until-`MAX_ATTEMPTS` loop (see [AutoCreateWorker]'s own KDoc for why that's
 * gone) - once that happened, it silently poisoned the chain forever: every later message got
 * enqueued as a dependent of a job that already failed, so `doWork` never ran again for any of
 * them, no error, no notification, nothing - until the chain was cleared.
 * [ExistingWorkPolicy.APPEND_OR_REPLACE] fixes this at the source: it behaves exactly like
 * `APPEND` while the tail hasn't finished, but starts a brand-new chain instead of appending once
 * the tail *has* reached a terminal state (success, failure, or cancellation) - self-healing, not
 * just a one-time fix for the one chain that's already stuck (see `FfNotifyImporterApp` for that
 * one-time cleanup).
 *
 * [Constraints.setRequiredNetworkType] (`CONNECTED`) means a request sitting in the chain without
 * network simply waits rather than burning through [AutoCreateWorker.MAX_ATTEMPTS] retries for a
 * guaranteed-to-fail call - once connectivity returns, the whole backlog drains in order.
 */
object AutoCreateScheduler {

    const val UNIQUE_WORK_NAME = "auto_create"

    fun scheduleIfEnabled(context: Context, message: CapturedMessage) {
        if (!FireflyPrefs(context).isAutoCreateEnabled()) return

        val request = OneTimeWorkRequestBuilder<AutoCreateWorker>()
            .setInputData(AutoCreateWorker.inputData(message))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
