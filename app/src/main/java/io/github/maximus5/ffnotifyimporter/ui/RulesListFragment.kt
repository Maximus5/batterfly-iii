package io.github.maximus5.ffnotifyimporter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.FragmentRulesListBinding
import io.github.maximus5.ffnotifyimporter.rules.ParseRule
import io.github.maximus5.ffnotifyimporter.rules.RulesDb

class RulesListFragment : Fragment() {

    private var binding: FragmentRulesListBinding? = null
    private lateinit var adapter: RulesAdapter
    private lateinit var rulesDb: RulesDb

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentRulesListBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        rulesDb = RulesDb.getInstance(requireContext())

        adapter = RulesAdapter(onClick = ::openEditor, onOverflowClick = ::showOverflowMenu)
        binding.rulesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.rulesRecyclerView.adapter = adapter

        binding.addRuleButton.setOnClickListener { openEditor(null) }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val binding = binding ?: return
        val rules = rulesDb.loadAllOrdered()
        adapter.submitList(rules)
        binding.rulesEmptyText.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openEditor(rule: ParseRule?) {
        (activity as? SettingsActivity)?.openScreen(
            RuleEditFragment.newInstance(rule?.id),
            R.string.settings_rules_title,
        )
    }

    private fun showOverflowMenu(rule: ParseRule, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, ID_MOVE_UP, 0, R.string.rule_move_up)
        popup.menu.add(0, ID_MOVE_DOWN, 1, R.string.rule_move_down)
        popup.menu.add(0, ID_DUPLICATE, 2, R.string.rule_duplicate)
        popup.menu.add(0, ID_DELETE, 3, R.string.action_delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_MOVE_UP -> rulesDb.moveUp(rule.id)
                ID_MOVE_DOWN -> rulesDb.moveDown(rule.id)
                ID_DUPLICATE -> rulesDb.duplicate(rule.id)
                ID_DELETE -> rulesDb.delete(rule.id)
                else -> return@setOnMenuItemClickListener false
            }
            reload()
            true
        }
        popup.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private companion object {
        const val ID_MOVE_UP = 1
        const val ID_MOVE_DOWN = 2
        const val ID_DUPLICATE = 3
        const val ID_DELETE = 4
    }
}
