package io.github.maximus5.ffnotifyimporter.posting

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.network.transactionUrl
import io.github.maximus5.ffnotifyimporter.prefs.NotificationPrefs
import io.github.maximus5.ffnotifyimporter.ui.CreateTransactionActivity
import io.github.maximus5.ffnotifyimporter.ui.MainActivity
import java.math.BigDecimal

/**
 * System notifications for [AutoCreateWorker]'s three terminal outcomes - the whole point of
 * auto-create running with the app closed is that something still has to tell the user what
 * happened, since there is no in-app dialog to show it in (unlike the manual "Создать" button's
 * own OK dialog / duplicate-confirm dialog). One channel, `IMPORTANCE_DEFAULT` - not `_HIGH` (no
 * heads-up popup warranted for a background bookkeeping result), not `_LOW` either (a
 * skipped-duplicate or a failure is worth more attention than a silent badge).
 *
 * For [notifySuccess]/[notifySkippedDuplicate] both the whole-notification tap and the "Open
 * transaction" action open the app's own editor for that transaction ([editorIntent]), plus a second
 * "Open in Firefly III" action for the web page ([webIntent]); a failure
 * only opens the app ([MainActivity]). Their text includes the parsed amount/currency/
 * description by default - another real catch: without them, there was no way to tell from the
 * notification alone *why* two notifications were treated as different payments (or the same
 * one), only the resulting Firefly id. `NotificationPrefs.isShowFinancialInfoEnabled()` - the
 * user's own request, a dedicated "Notifications" settings screen, defaulting *off* for Google
 * Play's financial-app content guidelines - swaps that detailed text for a generic one (app label
 * only) when off; `isNotificationsEnabled()` (checked once, inside [show]) skips posting any of
 * these three outcomes at all.
 *
 * Every call is a no-op if [android.Manifest.permission.POST_NOTIFICATIONS] was never granted
 * (API 33+, requested from `ui.TransactionCreationFragment` when the user turns "Auto-create" on) -
 * [NotificationManagerCompat.notify] throws [SecurityException] in that case rather than silently
 * dropping it, so that's caught here explicitly; a missing permission should never crash a
 * background worker over something this non-critical.
 */
object AutoCreateNotifications {

    private const val CHANNEL_ID = "auto_create"

    fun notifySuccess(
        baseContext: Context,
        baseUrl: String,
        message: CapturedMessage,
        amount: BigDecimal,
        currencyCode: String,
        description: String,
        transactionId: String,
        ruleLabel: String? = null,
    ) {
        val context = baseContext.withAppLocale()
        val text = if (NotificationPrefs(context).isShowFinancialInfoEnabled()) {
            withRuleLine(context, context.getString(R.string.auto_create_success_text, message.appLabel, amount.toPlainString(), currencyCode, description, transactionId), ruleLabel)
        } else {
            withRuleLine(context, context.getString(R.string.auto_create_success_text_generic, message.appLabel), ruleLabel)
        }
        show(
            context,
            id = message.hashCode(),
            title = context.getString(R.string.auto_create_success_title),
            text = text,
            pendingIntent = editorIntent(context, message, transactionId),
            action = editorIntent(context, message, transactionId),
            webAction = webIntent(context, message, baseUrl, transactionId),
        )
    }

    /** [existingTransactionId] is the transaction the skip was *against* - the action button jumps
     * straight to it, since confirming whether it's really the same payment means looking at that
     * transaction, not the app's own message list (the tap target still just opens the app).
     * [amount]/[currencyCode]/[description] are the *incoming* (skipped) draft's own fields, not
     * the existing transaction's - a real on-device case prompted this: two notifications from the
     * same app, worded differently enough that the dedup hash didn't match each other, each
     * silently auto-created its own transaction. Seeing the skipped draft's own description right
     * in the notification (without having to open Firefly and compare by hand) is what makes a
     * case like that diagnosable at a glance. */
    fun notifySkippedDuplicate(
        baseContext: Context,
        baseUrl: String,
        message: CapturedMessage,
        amount: BigDecimal,
        currencyCode: String,
        description: String,
        existingTransactionId: String,
        ruleLabel: String? = null,
    ) {
        val context = baseContext.withAppLocale()
        val text = if (NotificationPrefs(context).isShowFinancialInfoEnabled()) {
            withRuleLine(context, context.getString(R.string.auto_create_skipped_text, message.appLabel, amount.toPlainString(), currencyCode, description, existingTransactionId), ruleLabel)
        } else {
            withRuleLine(context, context.getString(R.string.auto_create_skipped_text_generic, message.appLabel), ruleLabel)
        }
        show(
            context,
            id = message.hashCode(),
            title = context.getString(R.string.auto_create_skipped_title),
            text = text,
            pendingIntent = editorIntent(context, message, existingTransactionId),
            action = editorIntent(context, message, existingTransactionId),
            webAction = webIntent(context, message, baseUrl, existingTransactionId),
        )
    }

    /**
     * Appends a "Rule: <label>" line to a notification body, in both the detailed and the generic
     * text (the user's own call: the rule name is what tells which rule matched, even with financial
     * info hidden). A rule label can name the account or bank, so it is the user's choice of rule
     * names that decides what a lock screen shows. Also used by [ReviewNotifications].
     */
    fun withRuleLine(context: Context, text: String, ruleLabel: String?): String =
        if (ruleLabel.isNullOrBlank()) text else text + "\n" + context.getString(R.string.notification_rule_line, ruleLabel)

    /** [reason] never carries amount/currency/description to begin with (just an app label and an
     * error string) - nothing here needs `NotificationPrefs.isShowFinancialInfoEnabled()`'s
     * generic-text branch the other two outcomes use. Still gated by `isNotificationsEnabled()`
     * (inside `show`) same as them. */
    fun notifyFailed(baseContext: Context, message: CapturedMessage, reason: String) {
        val context = baseContext.withAppLocale()
        show(
            context,
            id = message.hashCode(),
            title = context.getString(R.string.auto_create_failed_title),
            text = context.getString(R.string.auto_create_failed_text, message.appLabel, reason),
            pendingIntent = mainActivityIntent(context),
            action = null,
        )
    }

    /** Opens the app's own editor for the transaction ([CreateTransactionActivity.editIntent])
     * by itself (no [MainActivity] launched under it, so Back returns to where the user was) - via [DismissAndOpenActivity],
     * which also dismisses the notification (an action button doesn't do that by itself). The request
     * code is unique per message: extras are not part of a `PendingIntent`'s identity, so a shared
     * code would hand a second notification the first one's cached intent (see `ReviewNotifications`). */
    private fun editorIntent(context: Context, message: CapturedMessage, transactionId: String): PendingIntent {
        val editor = CreateTransactionActivity.editIntent(context, message, transactionId)
        val trampoline = DismissAndOpenActivity.intent(context, message.hashCode(), editor)
        return PendingIntent.getActivity(context, message.hashCode(), trampoline, PendingIntent.FLAG_IMMUTABLE)
    }

    /** Firefly III's own web page for [transactionId] - a second, optional action next to the in-app
     * editor one; dismisses the notification the same way. Own request code (+1) so it doesn't share
     * a cached `PendingIntent` with [editorIntent]. */
    private fun webIntent(context: Context, message: CapturedMessage, baseUrl: String, transactionId: String): PendingIntent {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(transactionUrl(baseUrl, transactionId)))
        val trampoline = DismissAndOpenActivity.intent(context, message.hashCode(), view)
        return PendingIntent.getActivity(context, message.hashCode() + 1, trampoline, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun mainActivityIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    /** [action] - when non-null, an explicit "Open transaction" button in the shade (collapsed or
     * expanded, no tap-and-wait needed) alongside [pendingIntent] (the whole-notification tap
     * target, always the app itself - the user's own call, not necessarily the same destination
     * as [action]). Does nothing at all when `NotificationPrefs.isNotificationsEnabled()` is off -
     * the user's own request, a dedicated "Notifications" settings screen - checked here rather
     * than at each of the three call sites above, so none of them can forget it. */
    private fun show(
        context: Context,
        id: Int,
        title: String,
        text: String,
        pendingIntent: PendingIntent,
        action: PendingIntent?,
        webAction: PendingIntent? = null,
    ) {
        if (!NotificationPrefs(context).isNotificationsEnabled()) return
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_app)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        if (action != null) {
            builder.addAction(R.drawable.ic_notification_sync, context.getString(R.string.create_open_transaction), action)
        }
        if (webAction != null) {
            builder.addAction(R.drawable.ic_notification_sync, context.getString(R.string.open_in_firefly), webAction)
        }
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted - the outcome still happened (recorded in
            // CreatedTransactionsDb/MessageStore either way), the user just won't be told about it
            // until they open the app themselves.
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.auto_create_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.auto_create_channel_description)
            },
        )
    }
}
