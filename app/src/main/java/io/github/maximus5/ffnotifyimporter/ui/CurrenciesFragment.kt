package io.github.maximus5.ffnotifyimporter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.DialogCurrencyEditBinding
import io.github.maximus5.ffnotifyimporter.databinding.FragmentCurrenciesBinding
import io.github.maximus5.ffnotifyimporter.network.FireflyApiClient
import io.github.maximus5.ffnotifyimporter.network.FireflyCurrency
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.rules.CurrenciesDb
import io.github.maximus5.ffnotifyimporter.rules.CurrencyEntry
import kotlinx.coroutines.launch

class CurrenciesFragment : Fragment() {

    private var binding: FragmentCurrenciesBinding? = null
    private lateinit var adapter: CurrenciesAdapter
    private lateinit var currenciesDb: CurrenciesDb

    /** Fetched once per screen session on first picker tap - same trade-off as the other
     * Firefly-sourced pickers in this app. */
    private var enabledCurrencies: List<FireflyCurrency>? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentCurrenciesBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        currenciesDb = CurrenciesDb.getInstance(requireContext())

        adapter = CurrenciesAdapter(onClick = ::showEditDialog, onOverflowClick = ::showOverflowMenu)
        binding.currenciesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.currenciesRecyclerView.adapter = adapter

        binding.addCurrencyButton.setOnClickListener { showEditDialog(null) }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        adapter.submitList(currenciesDb.loadAll())
    }

    private fun showEditDialog(existing: CurrencyEntry?) {
        val dialogBinding = DialogCurrencyEditBinding.inflate(LayoutInflater.from(requireContext()))
        dialogBinding.currencyCodeInput.setText(existing?.code.orEmpty())
        dialogBinding.currencySymbolInput.setText(existing?.symbol.orEmpty())
        dialogBinding.currencyCodeLayout.setEndIconOnClickListener {
            showFireflyCurrencyPicker(dialogBinding.currencyCodeInput, dialogBinding.currencySymbolInput)
        }

        // Cancel always rightmost/last (house convention, AGENTS.md) - Save sits in the negative
        // slot instead of positive so Cancel can take the positive (rightmost) one.
        AlertDialog.Builder(requireContext())
            .setTitle(if (existing != null) R.string.currency_edit_title else R.string.currency_add)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_save) { _, _ ->
                val code = dialogBinding.currencyCodeInput.text?.toString()?.trim().orEmpty().uppercase()
                val symbol = dialogBinding.currencySymbolInput.text?.toString()?.trim().orEmpty()
                if (code.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.currency_code_required, Toast.LENGTH_SHORT).show()
                    return@setNegativeButton
                }
                // Renaming the code (not just editing symbol) - drop the old row first so it
                // doesn't linger as a stale duplicate alongside the new one.
                if (existing != null && existing.code != code) currenciesDb.delete(existing.code)
                currenciesDb.add(CurrencyEntry(code, symbol))
                reload()
            }
            .setPositiveButton(android.R.string.cancel, null)
            .show()
    }

    /** The code field stays freely typeable (the table also holds symbols for currencies Firefly
     * doesn't list); picking from Firefly's *enabled* currencies additionally fills in Symbol from
     * Firefly's own record. `rules.CurrenciesDb` is a cache of that table - see
     * `posting.learnCurrencySymbols` for the automatic fill during matching. */
    private fun showFireflyCurrencyPicker(codeInput: EditText, symbolInput: EditText) {
        val context = requireContext()
        val prefs = FireflyPrefs(context)
        if (!prefs.isConfigured()) {
            Toast.makeText(context, R.string.rule_firefly_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        val cached = enabledCurrencies
        if (cached != null) {
            showFireflyCurrencyDialog(cached, codeInput, symbolInput)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = FireflyApiClient(prefs.getBaseUrl(), prefs.getToken()).listCurrencies()) {
                is FireflyApiClient.Result.Success -> {
                    val enabled = result.value.filter { it.enabled }
                    enabledCurrencies = enabled
                    showFireflyCurrencyDialog(enabled, codeInput, symbolInput)
                }
                is FireflyApiClient.Result.Failure ->
                    Toast.makeText(context, getString(R.string.firefly_pick_currency_failed, result.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showFireflyCurrencyDialog(currencies: List<FireflyCurrency>, codeInput: EditText, symbolInput: EditText) {
        if (currencies.isEmpty()) {
            Toast.makeText(requireContext(), R.string.firefly_no_currencies, Toast.LENGTH_LONG).show()
            return
        }
        val currentCode = codeInput.text?.toString()?.trim().orEmpty()
        val labels = currencies.map { c ->
            buildString {
                append(c.code)
                if (c.name.isNotBlank()) append(" - ").append(c.name)
                if (c.symbol.isNotBlank()) append(" (").append(c.symbol).append(")")
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rule_pick_currency)
            .setSingleChoiceItems(labels.toTypedArray(), currencies.indexOfFirst { it.code.equals(currentCode, ignoreCase = true) }) { dialog, which ->
                val picked = currencies[which]
                codeInput.setText(picked.code)
                symbolInput.setText(picked.symbol)
                dialog.dismiss()
            }
            .show()
    }

    /** Same overflow-menu interaction as `RulesListFragment`'s row menu, for visual/interaction
     * consistency across every list in the app - only "Delete" is needed here (no reorder/
     * duplicate the way rules have), but the menu itself is the same shape rather than a bespoke
     * standalone delete icon. */
    private fun showOverflowMenu(entry: CurrencyEntry, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, ID_DELETE, 0, R.string.action_delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_DELETE -> confirmDelete(entry)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun confirmDelete(entry: CurrencyEntry) {
        // Cancel always rightmost/last (house convention, AGENTS.md).
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.currency_delete_confirm_title, entry.code))
            .setNegativeButton(R.string.action_delete) { _, _ ->
                currenciesDb.delete(entry.code)
                reload()
            }
            .setPositiveButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private companion object {
        const val ID_DELETE = 1
    }
}
