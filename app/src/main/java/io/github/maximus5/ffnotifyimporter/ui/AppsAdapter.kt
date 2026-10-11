package io.github.maximus5.ffnotifyimporter.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.maximus5.ffnotifyimporter.databinding.ItemAppBinding

class AppsAdapter(
    private val onMonitoredChanged: (packageName: String, monitored: Boolean) -> Unit,
) : RecyclerView.Adapter<AppsAdapter.ViewHolder>() {

    private val allItems = mutableListOf<AppEntry>()
    private val items = mutableListOf<AppEntry>()
    private var query = ""

    fun submitList(newItems: List<AppEntry>) {
        allItems.clear()
        allItems.addAll(newItems)
        applyFilter()
    }

    fun hasAnyItems() = allItems.isNotEmpty()

    fun setFilter(newQuery: String) {
        query = newQuery.trim()
        applyFilter()
    }

    private fun applyFilter() {
        items.clear()
        items.addAll(
            if (query.isEmpty()) allItems
            else allItems.filter {
                it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
            },
        )
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: AppEntry) {
            binding.appIcon.setImageDrawable(entry.icon)
            binding.appLabel.text = entry.label
            binding.appMonitoredCheckbox.setOnCheckedChangeListener(null)
            binding.appMonitoredCheckbox.isChecked = entry.monitored
            binding.appMonitoredCheckbox.setOnCheckedChangeListener { _, isChecked ->
                // Keep both backing lists in sync so a recycled-and-rebound row, or a row coming
                // back after the filter is cleared, doesn't snap back to its stale loaded value.
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    items[position] = items[position].copy(monitored = isChecked)
                }
                val index = allItems.indexOfFirst { it.packageName == entry.packageName }
                if (index >= 0) allItems[index] = allItems[index].copy(monitored = isChecked)
                onMonitoredChanged(entry.packageName, isChecked)
            }
        }
    }
}
