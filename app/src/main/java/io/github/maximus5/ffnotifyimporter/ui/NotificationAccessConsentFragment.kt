package io.github.maximus5.ffnotifyimporter.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.FragmentNotificationAccessConsentBinding

/**
 * Explicit disclosure screen shown before `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` - the
 * system screen where the user actually grants notification-listener access. Required for Google
 * Play's review of apps requesting this permission: the app must explain, in its own UI, what it
 * does with notification content *before* sending the user to the system grant screen, not just
 * imply it or leave it to the system permission's own generic wording.
 *
 * Opened from [AppsFragment]'s "grant access" banner button instead of that button jumping
 * straight to system settings itself - [AppsFragment] still lives behind it, so declining here
 * (`consentNotNowButton`) just pops back to the exact same banner, nothing lost.
 */
class NotificationAccessConsentFragment : Fragment() {

    private var binding: FragmentNotificationAccessConsentBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentNotificationAccessConsentBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return

        binding.consentBodyText.text = getString(R.string.notification_access_consent_body)
        binding.consentAgreeButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        binding.consentNotNowButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
