package io.github.maximus5.ffnotifyimporter.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import io.github.maximus5.ffnotifyimporter.databinding.FragmentNotificationSettingsBinding
import io.github.maximus5.ffnotifyimporter.prefs.NotificationPrefs

/**
 * The user's own request: a dedicated screen for this app's own notifications
 * (`posting.AutoCreateNotifications`/`ReviewNotifications`), separate from `FireflySettingsFragment`'s
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
        updateFinancialSwitchEnabled()

        binding.showNotificationsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.setNotificationsEnabled(isChecked)
            updateFinancialSwitchEnabled()
        }
        binding.showFinancialInfoSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.setShowFinancialInfoEnabled(isChecked)
        }
        binding.openSystemSettingsButton.setOnClickListener { openSystemNotificationSettings() }
    }

    private fun updateFinancialSwitchEnabled() {
        binding?.showFinancialInfoSwitch?.isEnabled = binding?.showNotificationsSwitch?.isChecked == true
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
