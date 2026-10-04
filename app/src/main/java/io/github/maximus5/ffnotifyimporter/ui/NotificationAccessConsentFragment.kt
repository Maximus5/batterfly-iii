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
 * Two entry points, both leading here: [MainActivity] shows this as the very first screen on any
 * launch where access isn't granted yet (the user's own call - the app does nothing useful
 * without this permission, so the disclosure belongs front and center on a fresh install, not
 * buried behind Settings), and [AppsFragment]'s "grant access" banner button opens it pushed on
 * top of [SettingsActivity]'s back stack for a later re-prompt. [dismiss] tells the two apart by
 * whether there's a back stack entry to pop at all, rather than needing an explicit mode
 * argument: pushed from `AppsFragment` -> pop back to it; sitting as `MainActivity`'s *root*
 * fragment (nothing to pop) -> swap to `MessagesFragment` instead ([MainActivity.showMessages]).
 * [onResume] calls the same [dismiss] the moment access is already granted - covers both "Agree,
 * continue" returning from the system grant screen having actually granted it, *and* simply
 * reopening the app later after granting it some other way - either way this screen never
 * lingers once it has nothing left to ask for.
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
        binding.consentNotNowButton.setOnClickListener { dismiss() }
    }

    override fun onResume() {
        super.onResume()
        if (isNotificationListenerAccessGranted(requireContext())) dismiss()
    }

    private fun dismiss() {
        if (parentFragmentManager.backStackEntryCount > 0) {
            parentFragmentManager.popBackStack()
        } else {
            (activity as? MainActivity)?.showMessages()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
