package io.github.maximus5.ffnotifyimporter.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.maximus5.ffnotifyimporter.databinding.FragmentAppsBinding
import io.github.maximus5.ffnotifyimporter.prefs.MonitoredAppsPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppsFragment : Fragment() {

    private var binding: FragmentAppsBinding? = null
    private lateinit var monitoredAppsPrefs: MonitoredAppsPrefs
    private lateinit var adapter: AppsAdapter
    private var hasLoadedApps = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentAppsBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return

        monitoredAppsPrefs = MonitoredAppsPrefs(requireContext())
        adapter = AppsAdapter { packageName, monitored ->
            monitoredAppsPrefs.setMonitored(packageName, monitored)
        }
        binding.appsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.appsRecyclerView.adapter = adapter

        binding.notificationAccessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        updateNotificationAccessBanner()
        // Fragments hosted in ViewPager2 reach onResume only once they're the actual visible
        // page (an off-screen pre-created neighbor stops at onStart) - so this is the right
        // place to lazily pay for enumerating every installed app, instead of doing it eagerly
        // for a tab the user may never open this session.
        if (!hasLoadedApps) {
            hasLoadedApps = true
            loadInstalledApps()
        }
    }

    private fun updateNotificationAccessBanner() {
        val binding = binding ?: return
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(requireContext())
        val hasAccess = requireContext().packageName in enabledPackages
        binding.notificationAccessBanner.visibility = if (hasAccess) View.GONE else View.VISIBLE
    }

    /**
     * Enumerating every installed app and loading each one's icon is slow (can easily be a
     * couple hundred apps) - doing it on the main thread froze the first frame of the whole
     * activity. Build the list on a background dispatcher and only touch the UI once it's ready.
     */
    private fun loadInstalledApps() {
        val binding = binding ?: return
        val context = requireContext().applicationContext
        binding.appsLoadingSpinner.visibility = View.VISIBLE
        binding.appsEmptyText.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val entries = withContext(Dispatchers.Default) { buildAppEntries(context) }

            val current = this@AppsFragment.binding ?: return@launch
            adapter.submitList(entries)
            current.appsLoadingSpinner.visibility = View.GONE
            current.appsEmptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun buildAppEntries(context: Context): List<AppEntry> {
        val packageManager = context.packageManager

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)

        return resolved
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .mapNotNull { packageName ->
                try {
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    AppEntry(
                        packageName = packageName,
                        label = packageManager.getApplicationLabel(appInfo).toString(),
                        icon = packageManager.getApplicationIcon(appInfo),
                        monitored = monitoredAppsPrefs.isMonitored(packageName),
                    )
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
            }
            // Monitored apps float to the top - otherwise it's hard to tell at a glance what's
            // actually being watched in a list of a couple hundred installed apps.
            .sortedWith(compareByDescending<AppEntry> { it.monitored }.thenBy { it.label.lowercase() })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
