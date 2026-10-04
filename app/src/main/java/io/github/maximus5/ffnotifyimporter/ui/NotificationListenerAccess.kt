package io.github.maximus5.ffnotifyimporter.ui

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/** Whether this app currently has notification-listener access granted - the one shared check
 * [MainActivity] (gates first launch on it), [AppsFragment] (its "grant access" banner), and
 * [NotificationAccessConsentFragment] (auto-dismisses once granted) all need. */
fun isNotificationListenerAccessGranted(context: Context): Boolean =
    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
