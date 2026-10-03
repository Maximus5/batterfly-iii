package io.github.maximus5.ffnotifyimporter.ui

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Android 15+ forces the window to draw edge-to-edge with no permanent way to opt out (the
 * windowOptOutEdgeToEdgeEnforcement theme flag is explicitly temporary - Google may remove it).
 * So don't fight the window; draw edge-to-edge like the platform wants, and reserve space for
 * the status/navigation bars ourselves via insets padding on [root]. That keeps the classic
 * non-overlapping look the user wants, regardless of API level.
 *
 * Also folds in the IME (keyboard) inset, not just the system bars: under edge-to-edge, the
 * classic `android:windowSoftInputMode="adjustResize"` auto-resize-around-the-keyboard behavior
 * no longer happens for free - the app has to account for `Type.ime()` itself, or a focused
 * field at the bottom of a scrollable form ends up hidden behind the keyboard. Combining both
 * types in one `getInsets` call takes the larger of the two at the bottom edge, so this still
 * behaves exactly like the systemBars-only version when the keyboard is hidden.
 *
 * Call once per activity, right after setContentView. The activity's
 * `android:windowSoftInputMode` should be `adjustResize` for the IME inset to be dispatched
 * reliably.
 */
fun AppCompatActivity.useClassicSystemBars(root: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
        windowInsets
    }
}
