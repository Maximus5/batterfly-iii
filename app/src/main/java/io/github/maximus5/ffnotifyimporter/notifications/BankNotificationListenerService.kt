package io.github.maximus5.ffnotifyimporter.notifications

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.maximus5.ffnotifyimporter.data.MessageStore
import io.github.maximus5.ffnotifyimporter.posting.AutoCreateScheduler
import io.github.maximus5.ffnotifyimporter.posting.ReviewNotificationScheduler
import io.github.maximus5.ffnotifyimporter.prefs.MonitoredAppsPrefs
import io.github.maximus5.ffnotifyimporter.prefs.SetupChecklistPrefs

/** `onNotificationPosted` is not only called for a genuinely new notification - the system also
 * replays every currently-active notification still sitting in the shade whenever this listener
 * (re)connects (`onListenerConnected`, implicit here - not overridden), which happens on its own
 * any time Android kills and restarts the service (no foreground service here, see TODO.md/
 * ARCHITECTURE.md's own note on that trade-off). A real on-device case the user caught: an
 * unswiped Google Wallet notification from over an hour earlier got replayed and captured again
 * as if it were new, well outside [MessageStore]'s dedup window, because [MessageStore.add] was
 * timestamped with "now" (when *this app* happened to receive it) rather than when the
 * notification was actually posted. */
class BankNotificationListenerService : NotificationListenerService() {

    private lateinit var monitoredAppsPrefs: MonitoredAppsPrefs

    override fun onCreate() {
        super.onCreate()
        monitoredAppsPrefs = MonitoredAppsPrefs(applicationContext)
    }

    /** The system (or a vendor power manager) can unbind the listener without the process dying;
     * asking to be rebound is the documented way back. A no-op when the user has revoked access. */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            requestRebind(ComponentName(this, BankNotificationListenerService::class.java))
        } catch (e: RuntimeException) {
            // Access revoked or the system refused: nothing to do, the user has to re-enable it.
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (!monitoredAppsPrefs.isMonitored(packageName)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val appLabel = AppLabelResolver.resolve(applicationContext, packageName)
        // sbn.postTime, not System.currentTimeMillis() - the system's own record of when this
        // notification was actually posted, which stays the same across a replay on listener
        // reconnect (see this class's own KDoc) - "now" would make a stale replay of the exact
        // same notification look brand new every time, well outside any dedup window.
        val recorded = MessageStore.shared.add(packageName, appLabel, title, text, sbn.postTime) ?: return
        // Retired here, not only when the Messages screen sees a non-empty list: a first message
        // can be captured and expire by retention before the app is ever opened.
        SetupChecklistPrefs(applicationContext).retire()
        AutoCreateScheduler.scheduleIfEnabled(applicationContext, recorded)
        ReviewNotificationScheduler.scheduleIfNeeded(applicationContext, recorded)
    }
}
