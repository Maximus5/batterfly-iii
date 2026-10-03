package io.github.maximus5.ffnotifyimporter.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.databinding.ItemMessageBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessagesAdapter(
    private val onClick: (CapturedMessage) -> Unit,
    private val onLongClick: (CapturedMessage) -> Boolean,
) : RecyclerView.Adapter<MessagesAdapter.ViewHolder>() {

    private var items: List<CapturedMessage> = emptyList()
    private val selected = mutableSetOf<CapturedMessage>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun submitList(newItems: List<CapturedMessage>) {
        items = newItems
        selected.retainAll(newItems.toSet())
        notifyDataSetChanged()
    }

    fun selectedMessages(): Set<CapturedMessage> = selected.toSet()

    fun selectedCount(): Int = selected.size

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        notifyDataSetChanged()
    }

    fun toggleSelection(message: CapturedMessage) {
        if (!selected.add(message)) selected.remove(message)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: CapturedMessage) {
            binding.messageAppLabel.text = message.appLabel
            binding.messageTime.text = timeFormat.format(Date(message.timestampMillis))
            binding.messageTitle.text = message.title
            binding.messageText.text = message.text
            binding.root.setBackgroundColor(
                if (message in selected) SELECTED_BACKGROUND else Color.TRANSPARENT,
            )
            binding.root.setOnClickListener { onClick(message) }
            binding.root.setOnLongClickListener { onLongClick(message) }
        }
    }

    private companion object {
        const val SELECTED_BACKGROUND = 0x338C9EFF
    }
}
