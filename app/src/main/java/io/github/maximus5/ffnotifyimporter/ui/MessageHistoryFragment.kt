package io.github.maximus5.ffnotifyimporter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import io.github.maximus5.ffnotifyimporter.data.MessageHistoryDb
import io.github.maximus5.ffnotifyimporter.data.MessageStore
import io.github.maximus5.ffnotifyimporter.data.RetentionController
import io.github.maximus5.ffnotifyimporter.databinding.FragmentMessageHistoryBinding
import io.github.maximus5.ffnotifyimporter.prefs.RetentionPrefs

class MessageHistoryFragment : Fragment() {

    private var binding: FragmentMessageHistoryBinding? = null
    private lateinit var retentionPrefs: RetentionPrefs

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentMessageHistoryBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        retentionPrefs = RetentionPrefs(requireContext())

        radioIdFor(retentionPrefs.getRetentionDays())?.let { binding.retentionOptions.check(it) }

        binding.retentionOptions.setOnCheckedChangeListener { _, checkedId ->
            val days = daysForRadioId(checkedId) ?: return@setOnCheckedChangeListener
            retentionPrefs.setRetentionDays(days)
            RetentionController.applyOnSettingChanged(
                retentionDays = days,
                store = MessageStore.shared,
                db = MessageHistoryDb.getInstance(requireContext()),
            )
        }
    }

    private fun radioIdFor(days: Int): Int? {
        val binding = binding ?: return null
        return when (days) {
            0 -> binding.retentionOff.id
            1 -> binding.retention1.id
            3 -> binding.retention3.id
            5 -> binding.retention5.id
            7 -> binding.retention7.id
            else -> null
        }
    }

    private fun daysForRadioId(radioId: Int): Int? {
        val binding = binding ?: return null
        return when (radioId) {
            binding.retentionOff.id -> 0
            binding.retention1.id -> 1
            binding.retention3.id -> 3
            binding.retention5.id -> 5
            binding.retention7.id -> 7
            else -> null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
