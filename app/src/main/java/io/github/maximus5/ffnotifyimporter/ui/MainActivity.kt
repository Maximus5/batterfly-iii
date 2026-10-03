package io.github.maximus5.ffnotifyimporter.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ forces the window to draw edge-to-edge with no permanent way to opt out
        // (the windowOptOutEdgeToEdgeEnforcement theme flag is explicitly temporary - Google may
        // remove it). So don't fight the window; draw edge-to-edge like the platform wants, and
        // reserve space for the status/navigation bars ourselves via insets padding below. That
        // keeps the classic non-overlapping look the user wants without depending on a flag that
        // could disappear.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            windowInsets
        }

        binding.viewPager.adapter = MainPagerAdapter(supportFragmentManager, lifecycle)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = getString(
                if (position == 0) R.string.tab_messages else R.string.tab_apps
            )
        }.attach()
    }

    // Messages first: it's the tab you actually want to check day to day, and unlike the
    // apps list (which enumerates every installed package) it's cheap to show immediately.
    private class MainPagerAdapter(
        fragmentManager: FragmentManager,
        lifecycle: Lifecycle,
    ) : FragmentStateAdapter(fragmentManager, lifecycle) {
        override fun getItemCount() = 2

        override fun createFragment(position: Int): Fragment =
            if (position == 0) MessagesFragment() else AppsFragment()
    }
}
