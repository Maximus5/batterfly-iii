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
import java.math.BigDecimal

/**
 * System notification for [ReviewNotificationWorker] - the counterpart to [AutoCreateNotifications]
 * for when auto-create is *off*: a rule still matched, so rather than silently leaving it for the
 * user to notice in the in-app Messages list, surface it right away with enough detail to act on
 * (amount/currency/description, same reasoning as [AutoCreateNotifications]'s own fields), and a
 * "Create transaction" action that jumps straight to [CreateTransactionActivity] - the same review/
 * edit screen the manual flow uses, not a silent auto-post (that's the whole point of auto-create
 * being off). Deliberately does *not* run a duplicate check first - that's left to the user via
 * [CreateTransactionActivity]'s own [checkForDuplicate] call on "Create transaction", same as any
 * other manual creation.
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
            .setAutoCancel(true)
            .setContentIntent(action)
            .addAction(R.drawable.ic_notification_sync, context.getString(R.string.test_match_create), action)
        try {
            NotificationManagerCompat.from(context).notify(message.hashCode(), builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted - the match is still sitting in the Messages list for
            // the user to find by hand, same fallback as AutoCreateNotifications.
        }
    }

    private fun createTransactionIntent(context: Context, message: CapturedMessage): PendingIntent {
        val intent = CreateTransactionActivity.intent(context, message).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
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
