package io.github.maximus5.ffnotifyimporter.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.FragmentFireflyConnectionBinding
import io.github.maximus5.ffnotifyimporter.network.FireflyApiClient
import io.github.maximus5.ffnotifyimporter.network.isCleartextUrl
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import kotlinx.coroutines.launch

/**
 * Base URL + personal access token - the only genuinely secret fields this app holds (AGENTS.md's
 * Secrets section). Split out of what used to be one `FireflySettingsFragment` - the user's own
 * call, prompted by an AI-Review security finding: the token field's password-reveal icon let
 * anyone looking at the phone read the *saved* token back out by opening this screen, and that
 * risk has nothing to do with the non-secret settings (approx-conversion tag, auto-create, dedup -
 * moved to [TransactionCreationFragment]), so there was no reason to protect that screen too.
 *
 * Three layered defenses here, deliberately not just one:
 * - [FragmentFireflyConnectionBinding.tokenInput] is **write-once** - [onViewCreated] never loads
 *   the real saved token back into the field, so there is no "read it back by opening the screen"
 *   at all, reveal icon or not (the icon stays, for checking what's being *freshly typed* before
 *   saving - a much lower-stakes reveal than an old secret nobody asked to see again). The field
 *   itself starts genuinely empty - what shows instead is its own `android:hint` (not the
 *   `TextInputLayout`'s floating label, which stays the static "Personal access token" - the same
 *   label-plus-inline-placeholder shape [urlInput]'s own hint/example pair already uses, enabled
 *   by that field's `app:expandedHintEnabled="false"`), set here at runtime to a masked
 *   placeholder (`firefly_field_token_saved_hint`, literal •••• characters plus "leave blank to
 *   keep it") whenever a token is already saved - visually distinct from "nothing saved yet"
 *   without ever displaying the real value. [save] only calls `FireflyPrefs.setToken` when
 *   something was actually typed, leaving the stored token untouched if the field is still blank -
 *   same "blank means don't touch it" convention [testConnection] also uses, so testing a
 *   URL-only change doesn't force retyping the token first.
 * - `FLAG_SECURE` ([setSecure], driven by [FragmentFireflyConnectionBinding.protectScreenshotsSwitch])
 *   blocks screenshots/screen recording - system or a malicious accessibility service - for as long
 *   as it's on. Defaults *on* every time this screen opens (not a persisted `FireflyPrefs` setting -
 *   deliberately not sticky, so there's no way to leave "documentation mode" on by accident) -
 *   the user's own follow-up request, after asking how to still capture a screenshot of this
 *   screen for documentation purposes. Window flags are Activity-wide, so this only ever touches
 *   them while *this* fragment is the one visible - [onPause] always clears it, regardless of the
 *   switch, so leaving this screen never leaves the flag set behind it on whatever's next.
 */
class FireflyConnectionFragment : Fragment() {

    private var binding: FragmentFireflyConnectionBinding? = null
    private lateinit var prefs: FireflyPrefs

    /** Same "confirm before losing unsaved edits" shape `RuleEditFragment.initialDraft`/
     * `hasUnsavedChanges` already uses - just [urlInput]/[tokenInput], not
     * [protectScreenshotsSwitch]: that one is deliberately never persisted at all (see this
     * class's own KDoc, "not sticky" by design), so toggling it alone is never an unsaved change
     * worth confirming - there's nothing for [save] to lose. [tokenInput] starts genuinely blank
     * every time (write-once design, same KDoc) and gets cleared again after a successful save, so
     * comparing it against a blank baseline still correctly flags "typed but not yet saved". */
    private var initialSnapshot: Pair<String, String>? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentFireflyConnectionBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        prefs = FireflyPrefs(requireContext())

        binding.urlInput.setText(prefs.getBaseUrl())
        updateTokenHint()
        updateCleartextWarning(prefs.getBaseUrl())

        binding.urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateCleartextWarning(s?.toString().orEmpty())
        })

        binding.protectScreenshotsSwitch.setOnCheckedChangeListener { _, isChecked -> setSecure(isChecked) }

        binding.testConnectionButton.setOnClickListener { testConnection() }
        binding.saveButton.setOnClickListener { save() }

        initialSnapshot = buildSnapshot()
        (activity as? SettingsActivity)?.setBackGuard { handleBackPressed() }
    }

    private fun buildSnapshot(): Pair<String, String>? {
        val binding = binding ?: return null
        return binding.urlInput.text?.toString()?.trim().orEmpty() to binding.tokenInput.text?.toString()?.trim().orEmpty()
    }

    private fun hasUnsavedChanges(): Boolean = buildSnapshot() != initialSnapshot

    /** Cancel always rightmost/last (house convention, AGENTS.md) - the real action (Discard)
     * goes in [AlertDialog.Builder.setNegativeButton] (left), Cancel in
     * [AlertDialog.Builder.setPositiveButton] (right). */
    private fun handleBackPressed() {
        if (hasUnsavedChanges()) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.discard_changes_title)
                .setMessage(R.string.discard_changes_message)
                .setNegativeButton(R.string.discard_changes_discard) { _, _ -> (activity as? SettingsActivity)?.leaveScreen() }
                .setPositiveButton(android.R.string.cancel, null)
                .show()
        } else {
            (activity as? SettingsActivity)?.leaveScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        setSecure(binding?.protectScreenshotsSwitch?.isChecked ?: true)
    }

    override fun onPause() {
        super.onPause()
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun setSecure(secure: Boolean) {
        val window = activity?.window ?: return
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun updateCleartextWarning(url: String) {
        binding?.cleartextWarning?.visibility = if (isCleartextUrl(url)) View.VISIBLE else View.GONE
    }

    private fun updateTokenHint() {
        binding?.tokenInput?.hint = if (prefs.getToken().isNotBlank()) getString(R.string.firefly_field_token_saved_hint) else null
    }

    /** Falls back to the *saved* token when the field is blank - same "blank means don't touch it"
     * reasoning [save] uses, so testing a URL-only change doesn't force retyping the token first. */
    private fun testConnection() {
        val binding = binding ?: return
        val url = binding.urlInput.text?.toString().orEmpty().trim()
        val typedToken = binding.tokenInput.text?.toString().orEmpty().trim()
        val token = typedToken.ifBlank { prefs.getToken() }
        if (url.isBlank() || token.isBlank()) {
            binding.testConnectionResult.text = getString(R.string.firefly_test_connection_failure, "URL/token")
            return
        }

        binding.testConnectionResult.text = null
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = FireflyApiClient(url, token).testConnection()) {
                is FireflyApiClient.Result.Success ->
                    this@FireflyConnectionFragment.binding?.testConnectionResult?.text =
                        getString(R.string.firefly_test_connection_success, result.value)
                is FireflyApiClient.Result.Failure ->
                    this@FireflyConnectionFragment.binding?.testConnectionResult?.text =
                        getString(R.string.firefly_test_connection_failure, result.message)
            }
        }
    }

    private fun save() {
        val binding = binding ?: return
        val url = binding.urlInput.text?.toString().orEmpty().trim()
        val typedToken = binding.tokenInput.text?.toString().orEmpty().trim()

        if (url.isBlank()) {
            binding.urlInput.error = getString(R.string.firefly_url_required)
            return
        }
        if (typedToken.isBlank() && prefs.getToken().isBlank()) {
            binding.tokenInput.error = getString(R.string.firefly_token_required)
            return
        }

        prefs.setBaseUrl(url)
        if (typedToken.isNotBlank()) {
            prefs.setToken(typedToken)
            binding.tokenInput.text?.clear()
            updateTokenHint()
        }

        initialSnapshot = buildSnapshot()
        Toast.makeText(requireContext(), R.string.firefly_settings_saved, Toast.LENGTH_SHORT).show()
        (activity as? SettingsActivity)?.leaveScreen()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? SettingsActivity)?.setBackGuard(null)
        binding = null
    }
}
