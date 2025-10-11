package ru.example.parentwatch.chat

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ru.example.parentwatch.R

/**
 * Adapter for chat messages RecyclerView
 */
class ChatAdapter(private val messages: List<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageText: TextView = itemView.findViewById(R.id.messageText)
        val timestampText: TextView = itemView.findViewById(R.id.timestampText)
        val messageContainer: LinearLayout = itemView.findViewById(R.id.messageContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]

        holder.messageText.text = message.text
        holder.timestampText.text = message.getFormattedTime()

        // Style message based on sender
        if (message.isFromChild()) {
            // Child's message - align right, different color
            (holder.itemView.layoutParams as RecyclerView.LayoutParams).apply {
                marginStart = 60
                marginEnd = 8
            }
            holder.messageContainer.gravity = Gravity.END
            holder.messageContainer.setBackgroundResource(R.drawable.message_bubble_child)
        } else {
            // Parent's message - align left, different color
            (holder.itemView.layoutParams as RecyclerView.LayoutParams).apply {
                marginStart = 8
                marginEnd = 60
            }
            holder.messageContainer.gravity = Gravity.START
            holder.messageContainer.setBackgroundResource(R.drawable.message_bubble_parent)
        }
    }

    override fun getItemCount(): Int = messages.size
}
