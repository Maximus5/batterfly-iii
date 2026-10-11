package io.github.maximus5.ffnotifyimporter.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

/**
 * Android 15+ forces the window to draw edge-to-edge with no permanent way to opt out (the
 * windowOptOutEdgeToEdgeEnforcement theme flag is explicitly temporary - Google may remove it).
 * So draw edge-to-edge like the platform wants: [root] reserves space for the status bar and
 * the display cutout/sides, but *not* for the navigation bar - content runs underneath it, which
 * is what lets the (translucent / transparent) navigation bar look like it does in other apps.
 * To keep the last item reachable, every scrolling container inside a fragment gets the
 * navigation-bar height as extra bottom padding with `clipToPadding=false` (see
 * [padBottomForNavigationBar]), and views tagged in XML are handled explicitly:
 * `skip_bottom_inset` (a scroller that sits above a pinned bar - the bar is padded instead),
 * `bottom_bar` (a pinned bottom bar: padded), `bottom_fab` (a floating button: bottom margin).
 *
 * The IME (keyboard) inset is folded in too: under edge-to-edge the classic
 * `adjustResize` no longer happens for free, so while the keyboard is up [root] gets the full IME
 * height as bottom padding (it already includes the navigation bar) and the scrollers drop their
 * own navigation-bar padding.
 *
 * Status/navigation bar icon colors are set explicitly from the theme's background brightness
 * instead of relying on the theme flag, which left white-on-white status text on some devices.
 *
 * Call once per activity, right after setContentView. The activity's
 * `android:windowSoftInputMode` should be `adjustResize` for the IME inset to be dispatched
 * reliably.
 */
fun AppCompatActivity.useClassicSystemBars(root: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)

    val lightBackground = MaterialColors.isColorLight(MaterialColors.getColor(root, android.R.attr.colorBackground))
    WindowCompat.getInsetsController(window, root).apply {
        isAppearanceLightStatusBars = lightBackground
        isAppearanceLightNavigationBars = lightBackground
    }

    ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
        val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
        val bottom = if (windowInsets.isVisible(WindowInsetsCompat.Type.ime())) ime.bottom else 0
        view.setPadding(bars.left, bars.top, bars.right, bottom)
        windowInsets
    }

    supportFragmentManager.registerFragmentLifecycleCallbacks(
        object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: android.os.Bundle?) {
                v.applyNavigationBarInsetsToContent()
            }
        },
        true,
    )
}

private const val TAG_SKIP = "skip_bottom_inset"
private const val TAG_BAR = "bottom_bar"
private const val TAG_FAB = "bottom_fab"

private fun View.applyNavigationBarInsetsToContent() {
    when (tag) {
        TAG_SKIP -> return
        TAG_BAR -> { padBottomForNavigationBar(clip = false); return }
        TAG_FAB -> { marginBottomForNavigationBar(); return }
    }
    if (this is ScrollView || this is NestedScrollView || this is RecyclerView) {
        padBottomForNavigationBar(clip = true)
        return
    }
    if (this is ViewGroup) {
        for (i in 0 until childCount) getChildAt(i).applyNavigationBarInsetsToContent()
    }
}

private fun navigationBarBottom(insets: WindowInsetsCompat): Int =
    if (insets.isVisible(WindowInsetsCompat.Type.ime())) 0 else insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom

private fun View.padBottomForNavigationBar(clip: Boolean) {
    val basePadding = paddingBottom
    if (clip) (this as? ViewGroup)?.clipToPadding = false
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        view.updatePadding(bottom = basePadding + navigationBarBottom(insets))
        insets
    }
    doOnAttach { it.requestApplyInsets() }
}

private fun View.marginBottomForNavigationBar() {
    val baseMargin = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        view.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = baseMargin + navigationBarBottom(insets) }
        insets
    }
    doOnAttach { it.requestApplyInsets() }
}
