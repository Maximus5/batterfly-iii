package io.github.maximus5.ffnotifyimporter.posting

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat

/**
 * Invisible trampoline behind the buttons of [AutoCreateNotifications]'s created / skipped-duplicate
 * notifications. A notification *action* never dismisses its notification by itself (only a tap on
 * the body does, via `setAutoCancel`), and once the transaction exists there is nothing left for
 * the notification to say - so every tap goes through here: cancel the notification, then start
 * the real target ([EXTRA_TARGET]) and finish. The editor is deliberately not put on top of a
 * freshly launched [MainActivity]: the user tapped to verify one transaction, and Back should return
 * to wherever they were (the app's own screens only if it was already open there), not flash the list. An activity (not a receiver/service) so the platform's notification-trampoline
 * restriction doesn't apply.
 */
class DismissAndOpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        NotificationManagerCompat.from(this).cancel(notificationId)

        val target = IntentCompat.getParcelableExtra(intent, EXTRA_TARGET, Intent::class.java)
        if (target != null) {
            startActivity(target)
        }
        finish()
    }

    companion object {
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val EXTRA_TARGET = "target"

        fun intent(context: Context, notificationId: Int, target: Intent): Intent =
            Intent(context, DismissAndOpenActivity::class.java)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                .putExtra(EXTRA_TARGET, target)
    }
}
