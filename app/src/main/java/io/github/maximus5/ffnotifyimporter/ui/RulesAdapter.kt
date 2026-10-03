package io.github.maximus5.ffnotifyimporter.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
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
            // Always show the source -> destination pair, even when one side is blank by design
            // (WITHDRAWAL/DEPOSIT only ever fill one side) - a blank side is filled with
            // "(no name)" for display only, the same placeholder Firefly itself shows for a null
            // account, so it reads as a familiar convention rather than empty space. This is
            // purely cosmetic for this list row; it's never written back into the rule or sent
            // anywhere - TransactionDraft.sourceName/destinationName stay null, unaffected.
            val noName = context.getString(R.string.rule_account_no_name)
            val source = rule.sourceAccountName.ifBlank { noName }
            val destination = rule.destinationAccountName.ifBlank { noName }
            val accounts = "$source → $destination"

            var rest = "${context.getString(rule.kind.labelRes())}: $accounts"
            if (!rule.enabled) rest += " · ${context.getString(R.string.rule_disabled_label)}"

            // App name bold and on its own line - it's the identity of the row (which app this
            // rule even applies to), everything else is detail about that.
            val subtitle = SpannableStringBuilder(appLabel).apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, appLabel.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append("\n").append(rest)
            }
            binding.ruleSubtitle.text = subtitle

            binding.ruleRowContent.setOnClickListener { onClick(rule) }
            binding.ruleOverflowButton.setOnClickListener { onOverflowClick(rule, it) }
        }
    }
}
