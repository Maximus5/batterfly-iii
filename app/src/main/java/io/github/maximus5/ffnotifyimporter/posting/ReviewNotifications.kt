package io.github.maximus5.ffnotifyimporter.posting

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.ui.CreateTransactionActivity
import io.github.maximus5.ffnotifyimporter.ui.MainActivity
import java.math.BigDecimal

/**
 * System notification for [ReviewNotificationWorker] - the counterpart to [AutoCreateNotifications]
 * for when auto-create is *off*: a rule still matched, so rather than silently leaving it for the
 * user to notice in the in-app Messages list, surface it right away with enough detail to act on
 * (amount/currency/description, same reasoning as [AutoCreateNotifications]'s own fields), and a
 * "Create transaction" action to act on it from the shade. **Fixed a real "the app is lying to me"
 * gap, the user's own catch**: that action used to jump straight to [CreateTransactionActivity]
 * with no duplicate check at all (deliberately, by the original design - left entirely to the
 * screen's own on-submit `checkForDuplicate`), so a message that was actually a duplicate of
 * something already created walked the user into a blank create screen that only found out when
 * Firefly's own server-side hash check rejected the post - no "Use Created"/"Open"/"Create anyway"
 * choice at all, just a bare rejection error. The action now opens [MainActivity.checkMessageIntent]
 * instead, which runs the exact same preemptive check a tap on the message's own row in the
 * Messages list runs (`MessagesFragment.showTestMatch`/`checkThenOpenCreateOrShowFound` - see
 * `MessagesFragment.consumePendingMessageCheck`, the other half of this) before deciding what to
 * show - the same duplicate-confirm dialog a list tap would get, or straight into
 * [CreateTransactionActivity] when there's genuinely nothing to warn about.
 *
 * **Fixed a real "stays forever, or vanishes too early" bug, the user's own catch**: this used to
 * carry `setAutoCancel(true)`, which only ever dismisses a notification on a tap of its *content*
 * area - tapping the "Create transaction" *action* button (what the user actually tapped) opens
 * [CreateTransactionActivity] the same way but does **not** auto-dismiss, so the notification sat
 * there even after a transaction was successfully created from it. `setAutoCancel` is gone now -
 * instead [cancel] is called explicitly by every place that actually links a transaction to
 * [message] (`CreateTransactionFragment.onSaveSuccess` for a create/edit, `MessagesFragment`'s "Use
 * Created" for a dedup hit) - the notification only disappears once something real happened to it,
 * not just because it was tapped open and then backed out of or left mid-edit.
 *
 * Own channel ([CHANNEL_ID]), separate from [AutoCreateNotifications]'s - different meaning ("here's
 * something to review" vs. "here's what happened automatically"), so the user can mute/configure
 * them independently.
 */
object ReviewNotifications {

    private const val CHANNEL_ID = "review_match"

    fun notifyMatch(
        context: Context,
        message: CapturedMessage,
        amount: BigDecimal,
        currencyCode: String,
        description: String,
    ) {
        ensureChannel(context)
        val text = context.getString(R.string.review_match_text, message.appLabel, amount.toPlainString(), currencyCode, description)
        val action = createTransactionIntent(context, message)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_sync)
            .setContentTitle(context.getString(R.string.review_match_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(action)
            .addAction(R.drawable.ic_notification_sync, context.getString(R.string.test_match_create), action)
        try {
            NotificationManagerCompat.from(context).notify(notificationId(message), builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted - the match is still sitting in the Messages list for
            // the user to find by hand, same fallback as AutoCreateNotifications.
        }
    }

    /** Dismisses the notification [notifyMatch] posted for [message], if any is still showing -
     * see this object's own KDoc for why this is called explicitly rather than relying on
     * `setAutoCancel`. A no-op (not an error) when there's nothing to cancel - either this message
     * never had a review notification (a plain in-app tap, no auto-create-off match behind it), or
     * it's already gone (dismissed by the user directly, or cancelled once already). */
    fun cancel(context: Context, message: CapturedMessage) {
        NotificationManagerCompat.from(context).cancel(notificationId(message))
    }

    /** [message] identity for notification purposes deliberately excludes
     * [CapturedMessage.fireflyTransactionId] - the same field [CapturedMessage]'s own KDoc already
     * excludes from every other identity check in this app - by zeroing it before hashing, rather
     * than relying on the caller to always pass a pre-link [message]. [notifyMatch] is only ever
     * called with that field null in the first place (a message that hasn't been linked yet can't
     * have one), but [cancel] is called *after* linking, from code that may be holding either the
     * original message or the just-returned, now-linked one - without this, [CapturedMessage]'s
     * default `hashCode()` would change the moment the id was set, and `cancel` would silently miss
     * the very notification it's meant to dismiss. */
    private fun notificationId(message: CapturedMessage): Int = message.copy(fireflyTransactionId = null).hashCode()

    private fun createTransactionIntent(context: Context, message: CapturedMessage): PendingIntent {
        val intent = MainActivity.checkMessageIntent(context, message)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.review_match_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.review_match_channel_description)
            },
        )
    }
}
