package io.github.maximus5.ffnotifyimporter.ui

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.FragmentTransactionCreationBinding
import io.github.maximus5.ffnotifyimporter.posting.isNotificationPermissionGranted
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs

/**
 * Everything [FireflyPrefs] holds *except* the base URL/token (see [FireflyConnectionFragment]'s
 * own KDoc) - split out together, the user's own call, prompted by an AI-Review finding about the
 * token field's reveal icon: none of these fields are secret, so there's no reason to carry them
 * behind the same `FLAG_SECURE`/write-once protection that screen now has.
 */
class TransactionCreationFragment : Fragment() {

    private var binding: FragmentTransactionCreationBinding? = null
    private lateinit var prefs: FireflyPrefs

    /** Requested the moment the user turns auto-create on, and also on opening this screen while
     * it's already on (the user's own catch: a switch already enabled from a build before this
     * check existed, or opening this screen is as good a moment as any to notice a later-revoked
     * permission - `ui.MainActivity` separately re-checks at every app start too, for a user who
     * never opens this screen again). Declining it doesn't block the switch itself - auto-create
     * still works without it, the user just won't see `posting.AutoCreateNotifications`' outcome
     * notifications (same as `BankNotificationListenerService`'s own "go grant this in system
     * settings yourself" permission, no in-app gate on functioning without it). */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentTransactionCreationBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        prefs = FireflyPrefs(requireContext())

        binding.approxTagInput.setText(prefs.getApproxConversionTag())
        binding.autoCreateSwitch.isChecked = prefs.isAutoCreateEnabled()
        binding.dedupEnabledSwitch.isChecked = prefs.isDedupEnabled()
        binding.dedupIncludeDescriptionSwitch.isChecked = prefs.isDedupIncludeDescription()
        binding.dedupWindowInput.setText(prefs.getDedupWindowMinutes().toString())

        binding.autoCreateSwitch.setOnCheckedChangeListener { _, isChecked -> if (isChecked) requestNotificationPermissionIfNeeded() }
        if (binding.autoCreateSwitch.isChecked) requestNotificationPermissionIfNeeded()

        binding.saveButton.setOnClickListener { save() }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (isNotificationPermissionGranted(requireContext())) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun save() {
        val binding = binding ?: return
        val dedupWindow = binding.dedupWindowInput.text?.toString()?.trim()?.toIntOrNull()

        if (dedupWindow == null || dedupWindow <= 0) {
            binding.dedupWindowInput.error = getString(R.string.firefly_dedup_window_invalid)
            return
        }

        prefs.setApproxConversionTag(binding.approxTagInput.text?.toString().orEmpty().trim())
        prefs.setAutoCreateEnabled(binding.autoCreateSwitch.isChecked)
        prefs.setDedupEnabled(binding.dedupEnabledSwitch.isChecked)
        prefs.setDedupIncludeDescription(binding.dedupIncludeDescriptionSwitch.isChecked)
        prefs.setDedupWindowMinutes(dedupWindow)

        Toast.makeText(requireContext(), R.string.firefly_settings_saved, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
