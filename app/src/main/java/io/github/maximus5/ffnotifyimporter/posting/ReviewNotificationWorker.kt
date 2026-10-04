package io.github.maximus5.ffnotifyimporter.posting

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs

/**
 * Background counterpart to [AutoCreateWorker] for when auto-create is *off* - a rule still
 * matched, so rather than leaving it to be noticed only by opening the app, this posts a system
 * notification ([ReviewNotifications.notifyMatch]) with an action to review and create it by hand
 * via `ui.CreateTransactionActivity`. Never posts a transaction itself and never runs a duplicate
 * check - that judgment is deliberately left to the user at creation time, same as any other
 * manual "Создать".
 *
 * Only ever enqueued through [ReviewNotificationScheduler], which also re-checks the auto-create
 * setting before scheduling at all - re-checked again here since the setting (or the connection)
 * may have changed while this sat in the queue. Not part of [AutoCreateScheduler]'s unique work
 * chain: there is no shared mutable state here (no Firefly POST, no dedup log write) for two of
 * these to race over, so there is nothing to serialize against.
 */
class ReviewNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = FireflyPrefs(applicationContext)
        if (prefs.isAutoCreateEnabled() || !prefs.isConfigured()) return Result.success()

        val message = capturedMessageFrom(inputData)
        val draft = matchRule(applicationContext, message) ?: return Result.success()

        val resolved = resolveAmountQuietly(draft, prefs)
        val amount = resolved.amount ?: return Result.success()

        ReviewNotifications.notifyMatch(applicationContext, message, amount, resolved.currencyCode, resolved.description)
        return Result.success()
    }

    companion object {
        fun inputData(message: CapturedMessage): Data = capturedMessageInputData(message)
    }
}
