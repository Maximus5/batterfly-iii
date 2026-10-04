package io.github.maximus5.ffnotifyimporter.posting

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Whether `POST_NOTIFICATIONS` is currently granted - API 33+ only, the permission didn't exist
 * as a runtime grant before that (a posted notification just always showed). Shared by every call
 * site that might need to (re-)prompt for it:
 * - `ui.FireflySettingsFragment`, the moment the "Auto-create" switch is turned on.
 * - `ui.MainActivity`, at every app start - the user's own catch: a switch that was already on
 *   (enabled in a build before this permission check existed, or the permission later revoked by
 *   hand in system settings) never triggers the toggle-listener path at all, so without this a
 *   user in that state would silently never see auto-create's own outcome notifications and never
 *   be told why.
 */
fun isNotificationPermissionGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
