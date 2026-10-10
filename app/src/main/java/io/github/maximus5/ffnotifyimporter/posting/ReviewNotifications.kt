package io.github.maximus5.ffnotifyimporter.posting

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.prefs.NotificationPrefs
import io.github.maximus5.ffnotifyimporter.ui.CreateTransactionActivity
import io.github.maximus5.ffnotifyimporter.ui.MainActivity
import java.math.BigDecimal

/**
 * System notification for [ReviewNotificationWorker] - the counterpart to [AutoCreateNotifications]
 * for when auto-create is *off*: a rule still matched, so rather than silently leaving it for the
 * user to notice in the in-app Messages list, surface it right away with enough detail to act on
 * (amount/currency/description, same reasoning as [AutoCreateNotifications]'s own fields), and a
 * "Review and create" action to act on it from the shade - relabeled from "Create transaction"
 * (now [R.string.review_match_action], distinct from `create_tx`'s own on-screen button string so
 * the two can read differently without one accidentally changing the other) the user's own call:
 * tapping it never posts directly, it only ever opens the review screen first (see below), so the
 * label should say that. The generic (`isShowFinancialInfoEnabled` off) body text dropped its own
 * now-redundant "tap to review and create transaction" phrasing for the same reason, down to a
 * plain "%1$s - transaction detected" - the action button already says what tapping does.
 *
 * **Fixed a real "the app is lying to me" gap, the user's own catch**: that action used to jump
 * straight to [CreateTransactionActivity]
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
 *
 * The user's own request, a dedicated "Notifications" settings screen: does nothing at all when
 * `NotificationPrefs.isNotificationsEnabled()` is off, and - defaulting *off* for Google Play's
 * financial-app content guidelines - swaps the detailed amount/currency/description text for a
 * generic one (app label only) unless `isShowFinancialInfoEnabled()` is on.
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
        val notificationPrefs = NotificationPrefs(context)
        if (!notificationPrefs.isNotificationsEnabled()) return
        ensureChannel(context)
        val text = if (notificationPrefs.isShowFinancialInfoEnabled()) {
            context.getString(R.string.review_match_text, message.appLabel, amount.toPlainString(), currencyCode, description)
        } else {
            context.getString(R.string.review_match_text_generic, message.appLabel)
        }
        val action = createTransactionIntent(context, message)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_sync)
            .setContentTitle(context.getString(R.string.review_match_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(action)
            .addAction(R.drawable.ic_notification_sync, context.getString(R.string.review_match_action), action)
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

    /** **Fixed a real "wrong message's extras" bug, the user's own catch**: `PendingIntent.
     * getActivity`'s identity (whether it returns a fresh `PendingIntent` or hands back an
     * already-cached one) is `(component, requestCode, action/data/categories)` - **extras are not
     * part of that comparison at all**. [MainActivity.checkMessageIntent]'s target (`MainActivity`,
     * no distinguishing action/data, just extras) is identical for every message, so a hardcoded
     * `requestCode = 0` here meant a *second* review notification's action silently reused the
     * *first* one's cached `PendingIntent` - extras included - rather than building a new one with
     * its own message's identity. In practice: tapping the second notification's "Create
     * transaction" opened straight into editing the *first* message's already-linked transaction,
     * saved there instead, and left the second notification's own entry completely untouched
     * (still showing in the shade, never linked in the app). [notificationId] is already a stable,
     * unique-per-message int (used for the notification id itself) - reusing it as the request code
     * here gives every message's action its own distinct `PendingIntent`. */
    private fun createTransactionIntent(context: Context, message: CapturedMessage): PendingIntent {
        val intent = MainActivity.checkMessageIntent(context, message)
        return PendingIntent.getActivity(context, notificationId(message), intent, PendingIntent.FLAG_IMMUTABLE)
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
