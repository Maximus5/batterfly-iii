package io.github.maximus5.ffnotifyimporter.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
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
 *   saving - a much lower-stakes reveal than an old secret nobody asked to see again). A saved
 *   token shows [FragmentFireflyConnectionBinding.tokenSavedHint] instead ("Token saved - leave
 *   blank to keep it") and the field starts empty; [save] only calls `FireflyPrefs.setToken` when
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
        binding.tokenSavedHint.visibility = if (prefs.getToken().isNotBlank()) View.VISIBLE else View.GONE
        updateCleartextWarning(prefs.getBaseUrl())

        binding.urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateCleartextWarning(s?.toString().orEmpty())
        })

        binding.protectScreenshotsSwitch.setOnCheckedChangeListener { _, isChecked -> setSecure(isChecked) }

        binding.testConnectionButton.setOnClickListener { testConnection() }
        binding.saveButton.setOnClickListener { save() }
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
            binding.tokenSavedHint.visibility = View.VISIBLE
        }

        Toast.makeText(requireContext(), R.string.firefly_settings_saved, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
