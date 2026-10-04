package io.github.maximus5.ffnotifyimporter.ui

import android.graphics.Color
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
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
    private val onOverflowClick: (CapturedMessage, View) -> Unit,
) : RecyclerView.Adapter<MessagesAdapter.ViewHolder>() {

    private var items: List<CapturedMessage> = emptyList()
    private val selected = mutableSetOf<CapturedMessage>()
    private var selectionMode = false

    fun submitList(newItems: List<CapturedMessage>) {
        items = newItems
        selected.retainAll(newItems.toSet())
        notifyDataSetChanged()
    }

    /** The per-row overflow menu (`onOverflowClick`) is hidden while a multi-select is in
     * progress (`handleLongClick`'s own flow in `MessagesFragment`) - the two interaction modes
     * would otherwise compete for the same tap. */
    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
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

        // android.text.format.DateFormat (not java.text's) so the date respects the device's
        // actual date/time settings (region's date order, 12h/24h clock) instead of guessing
        // from locale alone. Seconds are kept (unlike the system clock's own display) since
        // near-duplicate notifications from different apps for the same payment have shown up
        // seconds apart - losing that precision would make them look identical in this list.
        private val dateFormat = DateFormat.getDateFormat(binding.root.context)
        private val timeFormat = SimpleDateFormat(
            if (DateFormat.is24HourFormat(binding.root.context)) "HH:mm:ss" else "h:mm:ss a",
            Locale.getDefault(),
        )

        fun bind(message: CapturedMessage) {
            binding.messageAppLabel.text = message.appLabel
            val timestamp = Date(message.timestampMillis)
            binding.messageTime.text = "${dateFormat.format(timestamp)} ${timeFormat.format(timestamp)}"
            if (message.fireflyTransactionId != null) {
                binding.messageTransactionId.text = "#${message.fireflyTransactionId}"
                binding.messageTransactionId.visibility = View.VISIBLE
            } else {
                binding.messageTransactionId.visibility = View.GONE
            }
            binding.messageTitle.text = message.title
            binding.messageText.text = message.text
            binding.root.setBackgroundColor(
                if (message in selected) SELECTED_BACKGROUND else Color.TRANSPARENT,
            )
            binding.messageRowContent.setOnClickListener { onClick(message) }
            binding.messageRowContent.setOnLongClickListener { onLongClick(message) }
            binding.messageOverflowButton.visibility = if (selectionMode) View.GONE else View.VISIBLE
            binding.messageOverflowButton.setOnClickListener { onOverflowClick(message, it) }
        }
    }

    private companion object {
        const val SELECTED_BACKGROUND = 0x338C9EFF
    }
}
