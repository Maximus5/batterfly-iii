package com.mxmmsk.ffnotifyimporter.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mxmmsk.ffnotifyimporter.databinding.ItemAppBinding

class AppsAdapter(
    private val onMonitoredChanged: (packageName: String, monitored: Boolean) -> Unit,
) : RecyclerView.Adapter<AppsAdapter.ViewHolder>() {

    private var items: List<AppEntry> = emptyList()

    fun submitList(newItems: List<AppEntry>) {
        items = newItems
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
                onMonitoredChanged(entry.packageName, isChecked)
            }
        }
    }
}
