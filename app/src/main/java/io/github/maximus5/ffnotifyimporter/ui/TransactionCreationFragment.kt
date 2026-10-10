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
 *
 * [FragmentTransactionCreationBinding.allTransactionsTagInput] ("Add tags for all transactions
 * (comma separated)") - the user's own request: one or more tags merged into *every* created
 * transaction's tags, regardless of which rule matched, so tags wanted everywhere don't have to be
 * repeated in every single rule's own tags template (unlike
 * [FragmentTransactionCreationBinding.approxTagInput], which only ever applies to an amount this
 * app itself computed from a fetched exchange rate). The actual merge happens in `rules.RuleEngine`
 * (a plain `String` constructor parameter - see its own KDoc for why that keeps `rules` free of any
 * `FireflyPrefs`/`Context` dependency), read fresh from `FireflyPrefs.getAllTransactionsTags()` by
 * every caller that builds a `RuleEngine` - this screen's own field is placed after both dividers,
 * grouped with [approxTagInput] (moved here from the top of the screen) rather than with
 * auto-create/dedup above it - the user's own layout call. Both tag fields' explanatory text is a
 * plain caption *below* the field now, not the `TextInputEditText`'s own inline `android:hint` the
 * way other fields on this screen still use - the user's own catch: a long inline hint made the
 * field itself taller, pushing content down for no benefit over a caption that only takes space
 * when it's actually shown. */
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

        binding.autoCreateSwitch.isChecked = prefs.isAutoCreateEnabled()
        binding.dedupEnabledSwitch.isChecked = prefs.isDedupEnabled()
        binding.dedupIncludeDescriptionSwitch.isChecked = prefs.isDedupIncludeDescription()
        binding.dedupWindowInput.setText(prefs.getDedupWindowMinutes().toString())
        binding.allTransactionsTagInput.setText(prefs.getAllTransactionsTags())
        binding.approxTagInput.setText(prefs.getApproxConversionTag())

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

        prefs.setAutoCreateEnabled(binding.autoCreateSwitch.isChecked)
        prefs.setDedupEnabled(binding.dedupEnabledSwitch.isChecked)
        prefs.setDedupIncludeDescription(binding.dedupIncludeDescriptionSwitch.isChecked)
        prefs.setDedupWindowMinutes(dedupWindow)
        prefs.setAllTransactionsTags(binding.allTransactionsTagInput.text?.toString().orEmpty().trim())
        prefs.setApproxConversionTag(binding.approxTagInput.text?.toString().orEmpty().trim())

        Toast.makeText(requireContext(), R.string.firefly_settings_saved, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
