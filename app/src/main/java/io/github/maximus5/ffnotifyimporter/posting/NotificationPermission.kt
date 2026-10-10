package io.github.maximus5.ffnotifyimporter.posting

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Whether `POST_NOTIFICATIONS` is currently granted - API 33+ only, the permission didn't exist
 * as a runtime grant before that (a posted notification just always showed). Shared by every call
 * site that checks or requests it:
 * - `ui.NotificationSettingsFragment`, which explains what the notifications are for before
 *   launching the system dialog.
 * - `ui.TransactionCreationFragment`, the moment the "Auto-create" switch is turned on.
 * - `ui.MessagesFragment`, for the setup checklist's done state.
 */
fun isNotificationPermissionGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
