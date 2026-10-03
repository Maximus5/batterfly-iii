package com.mxmmsk.ffnotifyimporter.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mxmmsk.ffnotifyimporter.data.MessageStore
import com.mxmmsk.ffnotifyimporter.prefs.MonitoredAppsPrefs

class BankNotificationListenerService : NotificationListenerService() {

    private lateinit var monitoredAppsPrefs: MonitoredAppsPrefs

    override fun onCreate() {
        super.onCreate()
        monitoredAppsPrefs = MonitoredAppsPrefs(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (!monitoredAppsPrefs.isMonitored(packageName)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val appLabel = AppLabelResolver.resolve(applicationContext, packageName)
        MessageStore.shared.add(packageName, appLabel, title, text)
    }
}
