package ru.example.childwatch.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ru.example.childwatch.R

class ChatAdapter(
    private val messages: MutableList<ChatMessage>,
    private val currentUser: String
) : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    private fun isOutgoing(message: ChatMessage) = message.sender == currentUser

    override fun getItemViewType(position: Int): Int {
        return if (isOutgoing(messages[position])) VIEW_TYPE_OUTGOING else VIEW_TYPE_INCOMING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layoutId = if (viewType == VIEW_TYPE_OUTGOING) {
            R.layout.item_message_outgoing
        } else {
            R.layout.item_message_incoming
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return MessageViewHolder(view, viewType == VIEW_TYPE_OUTGOING)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    inner class MessageViewHolder(itemView: View, private val isOutgoing: Boolean) :
        RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        private val timestampText: TextView = itemView.findViewById(R.id.timestampText)
        private val senderText: TextView? = itemView.findViewById(R.id.senderText)
        private val statusText: TextView? = itemView.findViewById(R.id.statusText)

        fun bind(message: ChatMessage) {
            messageText.text = message.text
            timestampText.text = message.getFormattedTime()

            if (!isOutgoing) {
                senderText?.apply {
                    visibility = View.VISIBLE
                    text = message.getSenderName()
                }
            } else {
                senderText?.visibility = View.GONE
            }

            statusText?.let { view ->
                if (isOutgoing) {
                    view.visibility = View.VISIBLE
                    // Показываем статус в зависимости от состояния сообщения
                    view.text = when (message.status) {
                        ChatMessage.MessageStatus.SENDING -> "🕐"
                        ChatMessage.MessageStatus.SENT -> "✓"
                        ChatMessage.MessageStatus.DELIVERED -> "✓✓"
                        ChatMessage.MessageStatus.READ -> "✓✓"
                        ChatMessage.MessageStatus.FAILED -> "❌"
                    }
                    // Меняем цвет для прочитанных сообщений
                    view.setTextColor(
                        if (message.status == ChatMessage.MessageStatus.READ) {
                            0xFF4CAF50.toInt() // Зелёный для прочитанных
                        } else {
                            0xFFB3B3B3.toInt() // Серый для остальных
                        }
                    )
                } else {
                    view.visibility = View.GONE
                }
            }
        }
    }

    companion object {
        private const val VIEW_TYPE_INCOMING = 1
        private const val VIEW_TYPE_OUTGOING = 2
    }
}
