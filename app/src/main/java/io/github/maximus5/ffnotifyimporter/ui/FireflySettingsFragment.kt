package io.github.maximus5.ffnotifyimporter.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.FragmentFireflySettingsBinding
import io.github.maximus5.ffnotifyimporter.network.FireflyApiClient
import io.github.maximus5.ffnotifyimporter.network.isCleartextUrl
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import kotlinx.coroutines.launch

/** Base URL + token, approximate-conversion tag, and the auto-create/dedup toggles - everything
 * [FireflyPrefs] holds. One form, one explicit Save (not autosave-per-keystroke like
 * `MessageHistoryFragment`'s radio group) since several of these fields are text the user is
 * still typing/correcting, not a single tap choice. */
class FireflySettingsFragment : Fragment() {

    private var binding: FragmentFireflySettingsBinding? = null
    private lateinit var prefs: FireflyPrefs

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentFireflySettingsBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        prefs = FireflyPrefs(requireContext())

        binding.urlInput.setText(prefs.getBaseUrl())
        binding.tokenInput.setText(prefs.getToken())
        binding.approxTagInput.setText(prefs.getApproxConversionTag())
        binding.autoCreateSwitch.isChecked = prefs.isAutoCreateEnabled()
        binding.dedupEnabledSwitch.isChecked = prefs.isDedupEnabled()
        binding.dedupIncludeDescriptionSwitch.isChecked = prefs.isDedupIncludeDescription()
        binding.dedupWindowInput.setText(prefs.getDedupWindowMinutes().toString())
        updateCleartextWarning(prefs.getBaseUrl())

        binding.urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateCleartextWarning(s?.toString().orEmpty())
        })

        binding.testConnectionButton.setOnClickListener { testConnection() }
        binding.saveButton.setOnClickListener { save() }
    }

    private fun updateCleartextWarning(url: String) {
        binding?.cleartextWarning?.visibility = if (isCleartextUrl(url)) View.VISIBLE else View.GONE
    }

    private fun testConnection() {
        val binding = binding ?: return
        val url = binding.urlInput.text?.toString().orEmpty().trim()
        val token = binding.tokenInput.text?.toString().orEmpty().trim()
        if (url.isBlank() || token.isBlank()) {
            binding.testConnectionResult.text = getString(R.string.firefly_test_connection_failure, "URL/token")
            return
        }

        binding.testConnectionResult.text = null
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = FireflyApiClient(url, token).testConnection()) {
                is FireflyApiClient.Result.Success ->
                    this@FireflySettingsFragment.binding?.testConnectionResult?.text =
                        getString(R.string.firefly_test_connection_success, result.value)
                is FireflyApiClient.Result.Failure ->
                    this@FireflySettingsFragment.binding?.testConnectionResult?.text =
                        getString(R.string.firefly_test_connection_failure, result.message)
            }
        }
    }

    private fun save() {
        val binding = binding ?: return
        val url = binding.urlInput.text?.toString().orEmpty().trim()
        val token = binding.tokenInput.text?.toString().orEmpty().trim()
        val dedupWindow = binding.dedupWindowInput.text?.toString()?.trim()?.toIntOrNull()

        if (url.isBlank()) {
            binding.urlInput.error = getString(R.string.firefly_url_required)
            return
        }
        if (token.isBlank()) {
            binding.tokenInput.error = getString(R.string.firefly_token_required)
            return
        }
        if (dedupWindow == null || dedupWindow <= 0) {
            binding.dedupWindowInput.error = getString(R.string.firefly_dedup_window_invalid)
            return
        }

        prefs.setBaseUrl(url)
        prefs.setToken(token)
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
