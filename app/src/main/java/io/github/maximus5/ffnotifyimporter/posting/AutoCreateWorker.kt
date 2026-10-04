package io.github.maximus5.ffnotifyimporter.posting

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.data.MessageStore
import io.github.maximus5.ffnotifyimporter.network.FireflyApiClient
import io.github.maximus5.ffnotifyimporter.network.FireflyTransactionRequest
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.rules.CreatedTransactionsDb
import io.github.maximus5.ffnotifyimporter.rules.TransactionDraft
import io.github.maximus5.ffnotifyimporter.rules.transactionDedupHash
import java.math.BigDecimal

/**
 * Background counterpart to `ui.MessagesFragment`'s manual "Создать" button - auto-posts a
 * transaction for a captured notification without the user opening the app at all, when
 * [FireflyPrefs.isAutoCreateEnabled]. Only ever enqueued through [AutoCreateScheduler], which is
 * also what guarantees these never run two at a time (see its own KDoc, the user's own explicit
 * requirement) - this class assumes it has the dedup log/Firefly to itself for the duration of
 * [doWork].
 *
 * Decided design (asked the user directly, not assumed, see TODO.md): a likely duplicate is
 * *skipped*, not posted and not auto-resolved - unlike the manual path's "create anyway"
 * confirmation, there is no human here to make that call, so the safer default is "don't",
 * surfaced as a system notification ([AutoCreateNotifications.notifySkippedDuplicate]) so the user
 * can still decide by hand (`checkIfCreated`/"Create Again"). A genuine success also gets a system
 * notification - manual creation's own feedback is the in-app OK dialog, which makes no sense for
 * something that happened with the app not even open.
 *
 * [Result.retry] on a failed Firefly call *or* a failed dedup-check verification
 * ([DuplicateCheckResult.SearchFailed] - a local-cache hit that couldn't be confirmed against
 * Firefly, see that type's own KDoc for why this is no longer treated as "assume no duplicate"),
 * up to [MAX_ATTEMPTS] - this is the actual "WorkManager for retrying failed API calls" use
 * AGENTS.md's own stack note was written for.
 * Capped rather than left to WorkManager's default indefinite retry: [AutoCreateScheduler]
 * enqueues every message into one single `ExistingWorkPolicy.APPEND` chain, so a stuck-retrying
 * item would otherwise block every later notification's auto-create attempt forever (a dead/
 * revoked token would silently stall the entire feature, not just one message) - exceeding the
 * cap gives up ([Result.failure]) instead, unblocking the chain, and tells the user once via
 * notification rather than retrying it forever in silence.
 */
class AutoCreateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = FireflyPrefs(applicationContext)
        // Re-checked here, not just at enqueue time (AutoCreateScheduler) - the setting, or the
        // connection itself, may have changed while this sat in the queue behind an earlier item.
        if (!prefs.isAutoCreateEnabled() || !prefs.isConfigured()) return Result.success()

        val message = capturedMessageFrom(inputData)
        val draft = matchRule(applicationContext, message) ?: return Result.success()

        val resolved = resolveAmountQuietly(draft, prefs)
        // Can't auto-post without a resolved amount - same "flag it, don't guess" precedent as
        // everywhere else LOCAL_AMOUNT_MISSING shows up, not a failure worth retrying or notifying
        // about (the message is still sitting in the Messages list for the user to handle by hand).
        val amount = resolved.amount ?: return Result.success()

        return when (val result = checkForDuplicate(applicationContext, resolved)) {
            is DuplicateCheckResult.None -> post(resolved, amount, message, prefs)
            is DuplicateCheckResult.SearchFailed -> {
                // Same retry/give-up shape as a failed createTransaction call below - a dedup
                // check that couldn't be verified is no longer "assume no duplicate" (the user's
                // own explicit call, see DuplicateCheckResult.SearchFailed's KDoc), so this gets
                // the same chance to recover from a transient network blip before giving up.
                if (runAttemptCount >= MAX_ATTEMPTS) {
                    AutoCreateNotifications.notifyFailed(applicationContext, message, result.message)
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
            is DuplicateCheckResult.Found -> {
                AutoCreateNotifications.notifySkippedDuplicate(
                    applicationContext, prefs.getBaseUrl(), message,
                    amount, resolved.currencyCode, resolved.description, result.id,
                )
                Result.success()
            }
            is DuplicateCheckResult.FoundMultiple -> {
                AutoCreateNotifications.notifySkippedDuplicate(
                    applicationContext, prefs.getBaseUrl(), message,
                    amount, resolved.currencyCode, resolved.description, result.mostRecentId,
                )
                Result.success()
            }
        }
    }

    private suspend fun post(draft: TransactionDraft, amount: BigDecimal, message: CapturedMessage, prefs: FireflyPrefs): Result {
        val request = FireflyTransactionRequest(
            type = draft.kind.name.lowercase(),
            dateIso = draft.date.toString(),
            amount = amount.toPlainString(),
            description = draft.description,
            currencyCode = draft.currencyCode,
            sourceId = draft.sourceAccountId,
            sourceName = draft.sourceName,
            destinationId = draft.destinationAccountId,
            destinationName = draft.destinationName,
            categoryName = draft.category,
            tags = draft.tags,
            notes = draft.notes.takeIf { it.isNotBlank() },
            foreignAmount = draft.foreignAmount?.toPlainString(),
            foreignCurrencyCode = draft.foreignCurrencyCode,
        )
        val client = FireflyApiClient(prefs.getBaseUrl(), prefs.getToken())
        return when (val result = client.createTransaction(request)) {
            is FireflyApiClient.Result.Success -> {
                CreatedTransactionsDb.getInstance(applicationContext).recordCreated(
                    hashWithDescription = transactionDedupHash(draft, true),
                    hashWithoutDescription = transactionDedupHash(draft, false),
                    transactionDateMillis = draft.date.toEpochMilli(),
                    fireflyTransactionId = result.value,
                )
                MessageStore.shared.setTransactionId(message, result.value)
                AutoCreateNotifications.notifySuccess(
                    applicationContext, prefs.getBaseUrl(), message,
                    amount, draft.currencyCode, draft.description, result.value,
                )
                Result.success()
            }
            is FireflyApiClient.Result.Failure -> {
                if (runAttemptCount >= MAX_ATTEMPTS) {
                    AutoCreateNotifications.notifyFailed(applicationContext, message, result.message)
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 5

        fun inputData(message: CapturedMessage): Data = capturedMessageInputData(message)
    }
}
