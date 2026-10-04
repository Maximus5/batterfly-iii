package io.github.maximus5.ffnotifyimporter.posting

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.data.MessageStore
import io.github.maximus5.ffnotifyimporter.network.ExchangeRateClient
import io.github.maximus5.ffnotifyimporter.network.FireflyApiClient
import io.github.maximus5.ffnotifyimporter.network.FireflyTransactionRequest
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.rules.CreatedTransactionsDb
import io.github.maximus5.ffnotifyimporter.rules.CurrenciesDb
import io.github.maximus5.ffnotifyimporter.rules.DraftIssue
import io.github.maximus5.ffnotifyimporter.rules.ParseOutcome
import io.github.maximus5.ffnotifyimporter.rules.RuleEngine
import io.github.maximus5.ffnotifyimporter.rules.RulesDb
import io.github.maximus5.ffnotifyimporter.rules.TransactionDraft
import io.github.maximus5.ffnotifyimporter.rules.applyApproxConversion
import io.github.maximus5.ffnotifyimporter.rules.transactionDedupHash

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
 * [Result.retry] on a failed Firefly call, up to [MAX_ATTEMPTS] - this is the actual
 * "WorkManager for retrying failed API calls" use AGENTS.md's own stack note was written for.
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

        val message = CapturedMessage(
            packageName = inputData.getString(KEY_PACKAGE_NAME).orEmpty(),
            appLabel = inputData.getString(KEY_APP_LABEL).orEmpty(),
            title = inputData.getString(KEY_TITLE).orEmpty(),
            text = inputData.getString(KEY_TEXT).orEmpty(),
            timestampMillis = inputData.getLong(KEY_TIMESTAMP_MILLIS, 0L),
        )

        val rules = RulesDb.getInstance(applicationContext).loadAllOrdered()
        val currencySymbols = CurrenciesDb.getInstance(applicationContext).symbolToCodeMap()
        val outcome = RuleEngine(rules, currencySymbols).match(message)
        val draft = (outcome as? ParseOutcome.Matched)?.draft ?: return Result.success()

        val resolved = resolveAmount(draft, prefs)
        // Can't auto-post without a resolved amount - same "flag it, don't guess" precedent as
        // everywhere else LOCAL_AMOUNT_MISSING shows up, not a failure worth retrying or notifying
        // about (the message is still sitting in the Messages list for the user to handle by hand).
        if (resolved.amount == null) return Result.success()

        return when (val result = checkForDuplicate(applicationContext, resolved)) {
            is DuplicateCheckResult.None, is DuplicateCheckResult.SearchFailed -> post(resolved, message, prefs)
            is DuplicateCheckResult.Found -> {
                AutoCreateNotifications.notifySkippedDuplicate(applicationContext, prefs.getBaseUrl(), message, result.id)
                Result.success()
            }
            is DuplicateCheckResult.FoundMultiple -> {
                AutoCreateNotifications.notifySkippedDuplicate(applicationContext, prefs.getBaseUrl(), message, result.mostRecentId)
                Result.success()
            }
        }
    }

    /** Same exchange-rate resolution `MessagesFragment.resolveAmountQuietly` does - no dialog to
     * update along the way, nothing to show until an outcome notification fires at the end. */
    private suspend fun resolveAmount(draft: TransactionDraft, prefs: FireflyPrefs): TransactionDraft {
        if (DraftIssue.LOCAL_AMOUNT_MISSING !in draft.issues) return draft
        val foreignCurrency = draft.foreignCurrencyCode ?: return draft
        return when (val result = ExchangeRateClient().fetchRate(foreignCurrency, draft.currencyCode, draft.date)) {
            is ExchangeRateClient.Result.Success -> applyApproxConversion(draft, result.rate, prefs.getApproxConversionTag())
            is ExchangeRateClient.Result.Failure -> draft
        }
    }

    private suspend fun post(draft: TransactionDraft, message: CapturedMessage, prefs: FireflyPrefs): Result {
        val amount = draft.amount ?: return Result.success()
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
                AutoCreateNotifications.notifySuccess(applicationContext, prefs.getBaseUrl(), message, result.value)
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

        private const val KEY_PACKAGE_NAME = "package_name"
        private const val KEY_APP_LABEL = "app_label"
        private const val KEY_TITLE = "title"
        private const val KEY_TEXT = "text"
        private const val KEY_TIMESTAMP_MILLIS = "timestamp_millis"

        fun inputData(message: CapturedMessage): Data = workDataOf(
            KEY_PACKAGE_NAME to message.packageName,
            KEY_APP_LABEL to message.appLabel,
            KEY_TITLE to message.title,
            KEY_TEXT to message.text,
            KEY_TIMESTAMP_MILLIS to message.timestampMillis,
        )
    }
}
