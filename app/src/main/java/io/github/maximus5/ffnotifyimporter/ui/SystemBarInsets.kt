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
 * Call once per activity, right after setContentView.
 */
fun AppCompatActivity.useClassicSystemBars(root: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
        val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
        windowInsets
    }
}
