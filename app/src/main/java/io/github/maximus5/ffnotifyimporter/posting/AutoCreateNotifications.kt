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

/**
 * System notifications for [AutoCreateWorker]'s three terminal outcomes - the whole point of
 * auto-create running with the app closed is that something still has to tell the user what
 * happened, since there is no in-app dialog to show it in (unlike the manual "Создать" button's
 * own OK dialog / duplicate-confirm dialog). One channel, `IMPORTANCE_DEFAULT` - not `_HIGH` (no
 * heads-up popup warranted for a background bookkeeping result), not `_LOW` either (a
 * skipped-duplicate or a failure is worth more attention than a silent badge).
 *
 * Every call is a no-op if [android.Manifest.permission.POST_NOTIFICATIONS] was never granted
 * (API 33+, requested from `FireflySettingsFragment` when the user turns "Auto-create" on) -
 * [NotificationManagerCompat.notify] throws [SecurityException] in that case rather than silently
 * dropping it, so that's caught here explicitly; a missing permission should never crash a
 * background worker over something this non-critical.
 */
object AutoCreateNotifications {

    private const val CHANNEL_ID = "auto_create"

    fun notifySuccess(context: Context, baseUrl: String, message: CapturedMessage, transactionId: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(transactionUrl(baseUrl, transactionId)))
        show(
            context,
            id = message.hashCode(),
            title = context.getString(R.string.auto_create_success_title),
            text = context.getString(R.string.auto_create_success_text, message.appLabel, transactionId),
            pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE),
        )
    }

    fun notifySkippedDuplicate(context: Context, message: CapturedMessage, existingTransactionId: String) {
        show(
            context,
            id = message.hashCode(),
            title = context.getString(R.string.auto_create_skipped_title),
            text = context.getString(R.string.auto_create_skipped_text, message.appLabel, existingTransactionId),
            pendingIntent = mainActivityIntent(context),
        )
    }

    fun notifyFailed(context: Context, message: CapturedMessage, reason: String) {
        show(
            context,
            id = message.hashCode(),
            title = context.getString(R.string.auto_create_failed_title),
            text = context.getString(R.string.auto_create_failed_text, message.appLabel, reason),
            pendingIntent = mainActivityIntent(context),
        )
    }

    private fun mainActivityIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun show(context: Context, id: Int, title: String, text: String, pendingIntent: PendingIntent) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
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
