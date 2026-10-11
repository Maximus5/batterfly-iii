package io.github.maximus5.ffnotifyimporter.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.FragmentNotificationSettingsBinding
import io.github.maximus5.ffnotifyimporter.posting.isNotificationPermissionGranted
import io.github.maximus5.ffnotifyimporter.prefs.NotificationPrefs

/**
 * The user's own request: a dedicated screen for this app's own notifications
 * (`posting.AutoCreateNotifications`/`ReviewNotifications`), separate from `TransactionCreationFragment`'s
 * auto-create toggle - that one decides *which* of those two notification kinds fires for a match,
 * this one decides whether either is allowed to post at all, and how much they say. Two switches,
 * autosave-per-tap (same shape `MessageHistoryFragment`'s radio group already uses) rather than an
 * explicit Save button - no typed/validated fields here, just two independent on/off choices.
 *
 * [showFinancialInfoSwitch] is disabled (not hidden - same "visible but not usable" convention as
 * the per-row overflow menu items elsewhere) whenever [showNotificationsSwitch] is off, since it
 * has nothing to apply to in that case - still shows its own last-saved value, just can't be
 * toggled until notifications are back on.
 */
class NotificationSettingsFragment : Fragment() {

    private var binding: FragmentNotificationSettingsBinding? = null
    private lateinit var prefs: NotificationPrefs

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentNotificationSettingsBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        prefs = NotificationPrefs(requireContext())

        binding.showNotificationsSwitch.isChecked = prefs.isNotificationsEnabled()
        binding.showFinancialInfoSwitch.isChecked = prefs.isShowFinancialInfoEnabled()
        binding.notifySkippedDuplicatesSwitch.isChecked = prefs.isNotifySkippedDuplicatesEnabled()
        updateFinancialSwitchEnabled()

        binding.showNotificationsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.setNotificationsEnabled(isChecked)
            updateFinancialSwitchEnabled()
        }
        binding.showFinancialInfoSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.setShowFinancialInfoEnabled(isChecked)
        }
        binding.notifySkippedDuplicatesSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.setNotifySkippedDuplicatesEnabled(isChecked)
        }
        binding.openSystemSettingsButton.setOnClickListener { openSystemNotificationSettings() }
        binding.batteryCardButton.setOnClickListener {
            openSystemScreen(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        binding.autoRevokeCardButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                openSystemScreen(
                    Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS)
                        .setData(Uri.fromParts("package", requireContext().packageName, null)),
                )
            }
        }
        binding.appInfoButton.setOnClickListener { openAppInfo() }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
        updateReliabilityState()
    }

    /** The two warning cards are shown only while the system state really can make the listener
     * miss notifications. What the app cannot read - vendor power savers such as Samsung's
     * "Sleeping apps" - is covered by the always-visible static hint instead. */
    private fun updateReliabilityState() {
        val binding = binding ?: return
        val context = requireContext()
        val powerManager = context.getSystemService(PowerManager::class.java)
        val batteryOptimized = powerManager != null && !powerManager.isIgnoringBatteryOptimizations(context.packageName)
        binding.batteryCard.visibility = if (batteryOptimized) View.VISIBLE else View.GONE
        val autoRevokeActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !context.packageManager.isAutoRevokeWhitelisted
        binding.autoRevokeCard.visibility = if (autoRevokeActive) View.VISIBLE else View.GONE
    }

    private fun openSystemScreen(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            openAppInfo()
        }
    }

    private fun openAppInfo() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", requireContext().packageName, null))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Nothing more basic to fall back to.
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                openSystemNotificationSettings()
            }
            updatePermissionState()
        }

    /** The explanation card is shown while notifications can't actually appear (runtime permission
     * missing, or switched off in system settings), and the in-app switches are disabled meanwhile -
     * visible so the user sees what they'd be enabling, not usable since they'd have no effect. The
     * system permission dialog is only ever launched from this card's button, after the explanation. */
    private fun updatePermissionState() {
        val binding = binding ?: return
        val context = requireContext()
        val permissionMissing = !isNotificationPermissionGranted(context)
        val blockedInSystem = !permissionMissing && !NotificationManagerCompat.from(context).areNotificationsEnabled()
        binding.permissionCard.visibility = if (permissionMissing || blockedInSystem) View.VISIBLE else View.GONE
        if (permissionMissing) {
            binding.permissionCardBody.setText(R.string.notifications_permission_card_body)
            binding.permissionCardButton.setText(R.string.notifications_permission_allow_button)
            binding.permissionCardButton.setOnClickListener {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            binding.permissionCardBody.setText(R.string.notifications_permission_card_body_blocked)
            binding.permissionCardButton.setText(R.string.notifications_permission_blocked_button)
            binding.permissionCardButton.setOnClickListener { openSystemNotificationSettings() }
        }
        binding.showNotificationsSwitch.isEnabled = !permissionMissing && !blockedInSystem
        updateFinancialSwitchEnabled()
    }

    private fun updateFinancialSwitchEnabled() {
        val binding = binding ?: return
        val dependentEnabled = binding.showNotificationsSwitch.isEnabled && binding.showNotificationsSwitch.isChecked
        binding.showFinancialInfoSwitch.isEnabled = dependentEnabled
        binding.notifySkippedDuplicatesSwitch.isEnabled = dependentEnabled
    }

    /** `ACTION_APP_NOTIFICATION_SETTINGS` - the whole app's notification settings (every channel:
     * `auto_create` and `review_match` both), not a single-channel deep link - channel-level mute/
     * importance/sound controls this screen deliberately doesn't duplicate. */
    private fun openSystemNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
