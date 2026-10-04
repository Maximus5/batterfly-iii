package io.github.maximus5.ffnotifyimporter.posting

import android.content.Context
import androidx.work.Data
import androidx.work.workDataOf
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.network.ExchangeRateClient
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.rules.CurrenciesDb
import io.github.maximus5.ffnotifyimporter.rules.DraftIssue
import io.github.maximus5.ffnotifyimporter.rules.ParseOutcome
import io.github.maximus5.ffnotifyimporter.rules.RuleEngine
import io.github.maximus5.ffnotifyimporter.rules.RulesDb
import io.github.maximus5.ffnotifyimporter.rules.TransactionDraft
import io.github.maximus5.ffnotifyimporter.rules.applyApproxConversion

private const val KEY_PACKAGE_NAME = "package_name"
private const val KEY_APP_LABEL = "app_label"
private const val KEY_TITLE = "title"
private const val KEY_TEXT = "text"
private const val KEY_TIMESTAMP_MILLIS = "timestamp_millis"

/** `WorkManager.Data` only holds primitives - every background worker that needs the originating
 * message reconstructs it from these same plain fields rather than trying to serialize a
 * [CapturedMessage] directly. Shared by [AutoCreateWorker] and `ReviewNotificationWorker`. */
fun capturedMessageInputData(message: CapturedMessage): Data = workDataOf(
    KEY_PACKAGE_NAME to message.packageName,
    KEY_APP_LABEL to message.appLabel,
    KEY_TITLE to message.title,
    KEY_TEXT to message.text,
    KEY_TIMESTAMP_MILLIS to message.timestampMillis,
)

fun capturedMessageFrom(data: Data): CapturedMessage = CapturedMessage(
    packageName = data.getString(KEY_PACKAGE_NAME).orEmpty(),
    appLabel = data.getString(KEY_APP_LABEL).orEmpty(),
    title = data.getString(KEY_TITLE).orEmpty(),
    text = data.getString(KEY_TEXT).orEmpty(),
    timestampMillis = data.getLong(KEY_TIMESTAMP_MILLIS, 0L),
)

/** Parses [message] fresh via [RuleEngine.match] - null if no rule matched. Shared by
 * [AutoCreateWorker] and `ReviewNotificationWorker`, both of which only ever receive a message's
 * plain fields (see [capturedMessageFrom]) and have to re-derive the draft themselves. */
fun matchRule(context: Context, message: CapturedMessage): TransactionDraft? {
    val rules = RulesDb.getInstance(context).loadAllOrdered()
    val currencySymbols = CurrenciesDb.getInstance(context).symbolToCodeMap()
    val outcome = RuleEngine(rules, currencySymbols).match(message)
    return (outcome as? ParseOutcome.Matched)?.draft
}

/** Same exchange-rate resolution `MessagesFragment.resolveAmountQuietly` does - no dialog/UI to
 * update along the way, since neither background worker using this has one. A failed lookup
 * silently keeps the original (still-unresolved) draft, same as `MessagesFragment`'s own
 * fallback. */
suspend fun resolveAmountQuietly(draft: TransactionDraft, prefs: FireflyPrefs): TransactionDraft {
    if (DraftIssue.LOCAL_AMOUNT_MISSING !in draft.issues) return draft
    val foreignCurrency = draft.foreignCurrencyCode ?: return draft
    return when (val result = ExchangeRateClient().fetchRate(foreignCurrency, draft.currencyCode, draft.date)) {
        is ExchangeRateClient.Result.Success -> applyApproxConversion(draft, result.rate, prefs.getApproxConversionTag())
        is ExchangeRateClient.Result.Failure -> draft
    }
}
