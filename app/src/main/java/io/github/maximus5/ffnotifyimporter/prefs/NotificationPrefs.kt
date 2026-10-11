package io.github.maximus5.ffnotifyimporter.prefs

import android.content.Context

/**
 * The user's own request: a dedicated "Notifications" settings screen controlling this app's own
 * system notifications (`posting.AutoCreateNotifications`/`ReviewNotifications`) - distinct from
 * [FireflyPrefs]'s auto-create toggle, which decides *which* of those two notification kinds fires
 * for a given match, not whether either is allowed to post at all.
 *
 * [isNotificationsEnabled] defaults to `true` - these notifications already existed and always
 * fired before this screen did, so the default keeps existing behavior unchanged; the user opts
 * out, not in. [isShowFinancialInfoEnabled] defaults to `false` - the user's own explicit choice,
 * for Google Play's financial-app content guidelines: amount/currency/description default to
 * hidden from the notification text (a generic line instead) even though the app already showed
 * them before this screen existed, since a notification can be seen by someone other than the
 * phone's owner (a locked screen, a second glance over someone's shoulder).
 */
class NotificationPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isNotificationsEnabled(): Boolean = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()
    }

    fun isShowFinancialInfoEnabled(): Boolean = prefs.getBoolean(KEY_SHOW_FINANCIAL_INFO, false)

    fun setShowFinancialInfoEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_FINANCIAL_INFO, enabled).apply()
    }

    /** Off by default - the user's own call: a skipped duplicate is usually just a second app
     * reporting the same payment, so the notification about it is mostly noise. The duplicate is
     * skipped either way; this only gates the notification. */
    fun isNotifySkippedDuplicatesEnabled(): Boolean = prefs.getBoolean(KEY_NOTIFY_SKIPPED_DUPLICATES, false)

    fun setNotifySkippedDuplicatesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_SKIPPED_DUPLICATES, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "notifications"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        private const val KEY_SHOW_FINANCIAL_INFO = "show_financial_info"
        private const val KEY_NOTIFY_SKIPPED_DUPLICATES = "notify_skipped_duplicates"
    }
}
