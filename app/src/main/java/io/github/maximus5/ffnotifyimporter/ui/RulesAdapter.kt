package io.github.maximus5.ffnotifyimporter.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.ItemRuleBinding
import io.github.maximus5.ffnotifyimporter.notifications.AppLabelResolver
import io.github.maximus5.ffnotifyimporter.rules.ParseRule

class RulesAdapter(
    private val onClick: (ParseRule) -> Unit,
    private val onOverflowClick: (ParseRule, View) -> Unit,
) : RecyclerView.Adapter<RulesAdapter.ViewHolder>() {

    private var items: List<ParseRule> = emptyList()

    fun submitList(newItems: List<ParseRule>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemRuleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(rule: ParseRule) {
            val context = binding.root.context
            binding.ruleLabel.text = rule.label

            val appLabel = rule.packageNames.firstOrNull()?.let { AppLabelResolver.resolve(context, it) }
                ?: context.getString(R.string.rule_apps_any)
            val account = rule.sourceAccountName ?: rule.destinationAccountName.orEmpty()
            val parts = listOf(context.getString(rule.kind.labelRes()), account, appLabel) +
                listOfNotNull(context.getString(R.string.rule_disabled_label).takeIf { !rule.enabled })
            binding.ruleSubtitle.text = parts.filter { it.isNotBlank() }.joinToString(" · ")

            binding.ruleRowContent.setOnClickListener { onClick(rule) }
            binding.ruleOverflowButton.setOnClickListener { onOverflowClick(rule, it) }
        }
    }
}
