package io.github.maximus5.ffnotifyimporter.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
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
        // Opt out of the edge-to-edge default (forced since targetSdk 35+): keep the
        // status bar and navigation bar always visible and reserve layout space for them,
        // instead of drawing content underneath. See TODO.md for the optional-fullscreen-mode
        // follow-up.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
