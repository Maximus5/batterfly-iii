package io.github.maximus5.ffnotifyimporter.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.maximus5.ffnotifyimporter.databinding.ItemAppBinding

class AppsAdapter(
    private val onMonitoredChanged: (packageName: String, monitored: Boolean) -> Unit,
) : RecyclerView.Adapter<AppsAdapter.ViewHolder>() {

    private val items = mutableListOf<AppEntry>()

    fun submitList(newItems: List<AppEntry>) {
        items.clear()
        items.addAll(newItems)
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
                // Keep the backing item in sync so a recycled-and-rebound row (scroll away and
                // back) doesn't snap back to the stale value it was first loaded with.
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    items[position] = items[position].copy(monitored = isChecked)
                }
                onMonitoredChanged(entry.packageName, isChecked)
            }
        }
    }
}
