package io.github.maximus5.ffnotifyimporter.ui

import android.graphics.Color
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
import io.github.maximus5.ffnotifyimporter.rules.BuiltInRules
import io.github.maximus5.ffnotifyimporter.rules.ParseRule

/** [onLongClick] starts/extends the multi-select `RulesListFragment` drives (move/enable/
 * disable/delete from the toolbar - see that class's own KDoc) - same three-callback shape
 * `MessagesAdapter` already uses for the identical gesture on the Messages list, keyed by
 * [ParseRule.id] (not the rule object itself) for selection tracking, matching how every mutating
 * `RulesDb` call in this fragment already addresses a rule - by id, never by value.
 *
 * A row whose id is in [BuiltInRules.IDS] (always the last 1-2 rows, appended by
 * `RulesListFragment.reload` - never a real [RulesDb] row) renders differently and ignores most
 * of the above: no overflow button (nothing to move/duplicate/delete - they're Kotlin constants,
 * not DB rows), long-press is swallowed rather than starting multi-select (they can't be acted on
 * as a group with real rules), and a tap calls [onToggleBuiltIn] instead of [onClick] - the one
 * shared enabled/disabled flag (`FireflyPrefs.isBuiltInRulesEnabled`) is the only thing about them
 * that's actually editable. A light tint on the row (not just the selected-row highlight) is the
 * "visually distinct" cue the user asked for, since the label text ("Default: ...") alone was
 * judged not enough to read as structurally different from an ordinary rule at a glance. */
class RulesAdapter(
    private val onClick: (ParseRule) -> Unit,
    private val onLongClick: (ParseRule) -> Boolean,
    private val onOverflowClick: (ParseRule, View) -> Unit,
    private val onToggleBuiltIn: (ParseRule) -> Unit,
) : RecyclerView.Adapter<RulesAdapter.ViewHolder>() {

    private var items: List<ParseRule> = emptyList()
    private val selected = mutableSetOf<String>()
    private var selectionMode = false

    fun submitList(newItems: List<ParseRule>) {
        items = newItems
        selected.retainAll(newItems.map { it.id }.toSet())
        notifyDataSetChanged()
    }

    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        notifyDataSetChanged()
    }

    fun selectedIds(): Set<String> = selected.toSet()

    fun selectedCount(): Int = selected.size

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        notifyDataSetChanged()
    }

    fun toggleSelection(rule: ParseRule) {
        if (!selected.add(rule.id)) selected.remove(rule.id)
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

            // The user's own catch: the "· disabled" text alone wasn't visible enough at a
            // glance. Dimming the whole row (same convention disabled form controls elsewhere in
            // this app already use) is the stronger, instantly-recognizable signal - kept
            // alongside the text, not instead of it, since alpha alone could just as easily read
            // as a rendering glitch without the label spelling out why.
            binding.root.alpha = if (rule.enabled) 1f else 0.5f

            val isBuiltIn = rule.id in BuiltInRules.IDS
            if (isBuiltIn) {
                binding.root.setBackgroundColor(BUILT_IN_BACKGROUND)
                binding.ruleRowContent.setOnClickListener { onToggleBuiltIn(rule) }
                binding.ruleRowContent.setOnLongClickListener { true }
                binding.ruleOverflowButton.visibility = View.GONE
            } else {
                binding.root.setBackgroundColor(if (rule.id in selected) SELECTED_BACKGROUND else Color.TRANSPARENT)
                binding.ruleRowContent.setOnClickListener { onClick(rule) }
                binding.ruleRowContent.setOnLongClickListener { onLongClick(rule) }
                binding.ruleOverflowButton.visibility = if (selectionMode) View.GONE else View.VISIBLE
                binding.ruleOverflowButton.setOnClickListener { onOverflowClick(rule, it) }
            }
        }
    }

    private companion object {
        const val SELECTED_BACKGROUND = 0x338C9EFF
        const val BUILT_IN_BACKGROUND = 0x14000000
    }
}
