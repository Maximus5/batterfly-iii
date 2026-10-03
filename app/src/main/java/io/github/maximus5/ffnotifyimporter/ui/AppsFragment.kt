package io.github.maximus5.ffnotifyimporter.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.maximus5.ffnotifyimporter.databinding.FragmentAppsBinding
import io.github.maximus5.ffnotifyimporter.prefs.MonitoredAppsPrefs

class AppsFragment : Fragment() {

    private var binding: FragmentAppsBinding? = null
    private lateinit var monitoredAppsPrefs: MonitoredAppsPrefs
    private lateinit var adapter: AppsAdapter

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

        loadInstalledApps()
    }

    override fun onResume() {
        super.onResume()
        updateNotificationAccessBanner()
    }

    private fun updateNotificationAccessBanner() {
        val binding = binding ?: return
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(requireContext())
        val hasAccess = requireContext().packageName in enabledPackages
        binding.notificationAccessBanner.visibility = if (hasAccess) View.GONE else View.VISIBLE
    }

    private fun loadInstalledApps() {
        val binding = binding ?: return
        val context = requireContext()
        val packageManager = context.packageManager

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)

        val entries = resolved
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
            .sortedBy { it.label.lowercase() }

        adapter.submitList(entries)
        binding.appsEmptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
