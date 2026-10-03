package io.github.maximus5.ffnotifyimporter.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.maximus5.ffnotifyimporter.databinding.ItemCurrencyBinding
import io.github.maximus5.ffnotifyimporter.rules.CurrencyEntry

class CurrenciesAdapter(
    private val onClick: (CurrencyEntry) -> Unit,
    private val onDeleteClick: (CurrencyEntry) -> Unit,
) : RecyclerView.Adapter<CurrenciesAdapter.ViewHolder>() {

    private var items: List<CurrencyEntry> = emptyList()

    fun submitList(newItems: List<CurrencyEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCurrencyBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemCurrencyBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: CurrencyEntry) {
            binding.currencyCode.text = entry.code
            binding.currencySymbol.text = entry.symbol
            binding.currencyRowContent.setOnClickListener { onClick(entry) }
            binding.currencyDeleteButton.setOnClickListener { onDeleteClick(entry) }
        }
    }
}
