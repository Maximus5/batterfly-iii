package io.github.maximus5.ffnotifyimporter.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.FragmentAboutBinding
import io.github.maximus5.ffnotifyimporter.posting.DiagnosticLog

/** Name, version, GitHub link - nothing user-configurable, so no prefs/db involved. */
class AboutFragment : Fragment() {

    private var binding: FragmentAboutBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return

        val versionName = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        binding.aboutVersion.text = getString(R.string.about_version, versionName ?: "?")

        val githubUrl = getString(R.string.about_github_url)
        binding.aboutGithubLink.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
        }

        val context = requireContext()
        binding.aboutDiagnosticSwitch.isChecked = DiagnosticLog.isEnabled(context)
        binding.aboutDiagnosticSwitch.setOnCheckedChangeListener { _, isChecked -> DiagnosticLog.setEnabled(context, isChecked) }
        binding.aboutCopyLog.setOnClickListener {
            val log = DiagnosticLog.read(context)
            if (log.isBlank()) {
                Toast.makeText(context, R.string.about_diagnostic_empty, Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Butterfly III diagnostic log", "Version ${versionName ?: "?"}\n$log"))
                Toast.makeText(context, R.string.about_diagnostic_copied, Toast.LENGTH_SHORT).show()
            }
        }
        binding.aboutClearLog.setOnClickListener { DiagnosticLog.clear(context) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
