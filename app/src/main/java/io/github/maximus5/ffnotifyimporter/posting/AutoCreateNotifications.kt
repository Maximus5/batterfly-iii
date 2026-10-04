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
 * The whole-notification tap always opens the app ([MainActivity]) on every outcome - the user's
 * own call, after trying tap-opens-Firefly first. [notifySuccess]/[notifySkippedDuplicate] also
 * carry an explicit "Open transaction" action button for jumping straight to Firefly's page
 * without going through the app first, and their text includes the parsed amount/currency/
 * description - another real catch: without them, there was no way to tell from the notification
 * alone *why* two notifications were treated as different payments (or the same one), only the
 * resulting Firefly id.
 *
 * Every call is a no-op if [android.Manifest.permission.POST_NOTIFICATIONS] was never granted
 * (API 33+, requested from `FireflySettingsFragment` when the user turns "Auto-create" on) -
 * [NotificationManagerCompat.notify] throws [SecurityException] in that case rather than silently
 * dropping it, so that's caught here explicitly; a missing permission should never crash a
 * background worker over something this non-critical.
 */
object AutoCreateNotifications {

    private const val CHANNEL_ID = "auto_create"

    fun notifySuccess(
        context: Context,
        baseUrl: String,
        message: CapturedMessage,
        amount: BigDecimal,
        currencyCode: String,
        description: String,
        transactionId: String,
    ) {
        show(
            context,
            id = message.hashCode(),
            title = context.getString(R.string.auto_create_success_title),
            text = context.getString(R.string.auto_create_success_text, message.appLabel, amount.toPlainString(), currencyCode, description, transactionId),
            pendingIntent = mainActivityIntent(context),
            action = transactionIntent(context, baseUrl, transactionId),
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
        context: Context,
        baseUrl: String,
        message: CapturedMessage,
        amount: BigDecimal,
        currencyCode: String,
        description: String,
        existingTransactionId: String,
    ) {
        show(
            context,
            id = message.hashCode(),
            title = context.getString(R.string.auto_create_skipped_title),
            text = context.getString(R.string.auto_create_skipped_text, message.appLabel, amount.toPlainString(), currencyCode, description, existingTransactionId),
            pendingIntent = mainActivityIntent(context),
            action = transactionIntent(context, baseUrl, existingTransactionId),
        )
    }

    fun notifyFailed(context: Context, message: CapturedMessage, reason: String) {
        show(
            context,
            id = message.hashCode(),
            title = context.getString(R.string.auto_create_failed_title),
            text = context.getString(R.string.auto_create_failed_text, message.appLabel, reason),
            pendingIntent = mainActivityIntent(context),
            action = null,
        )
    }

    private fun transactionIntent(context: Context, baseUrl: String, transactionId: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(transactionUrl(baseUrl, transactionId)))
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
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
     * as [action]). */
    private fun show(context: Context, id: Int, title: String, text: String, pendingIntent: PendingIntent, action: PendingIntent?) {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        if (action != null) {
            builder.addAction(R.drawable.ic_notification_sync, context.getString(R.string.create_open_transaction), action)
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
