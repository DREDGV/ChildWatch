package ru.example.childwatch.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ru.example.childwatch.R

class ChatAdapter(
    private val messages: MutableList<ChatMessage>,
    private val currentUser: String,
    private val onRetryMessage: ((ChatMessage) -> Unit)? = null
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
        private val retryButton: TextView? = itemView.findViewById(R.id.retryButton)

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
                    val statusLabel = when (message.status) {
                        ChatMessage.MessageStatus.SENDING ->
                            itemView.context.getString(R.string.chat_status_sending)
                        ChatMessage.MessageStatus.SENT ->
                            itemView.context.getString(R.string.chat_status_sent)
                        ChatMessage.MessageStatus.DELIVERED ->
                            itemView.context.getString(R.string.chat_status_delivered)
                        ChatMessage.MessageStatus.READ ->
                            itemView.context.getString(R.string.chat_status_read)
                        ChatMessage.MessageStatus.FAILED ->
                            itemView.context.getString(R.string.chat_status_failed)
                    }
                    view.text = statusLabel
                    val statusColor = if (message.status == ChatMessage.MessageStatus.READ) {
                        0xFF4CAF50.toInt()
                    } else {
                        0xFFB3B3B3.toInt()
                    }
                    view.setTextColor(statusColor)
                } else {
                    view.visibility = View.GONE
                }
            }

            retryButton?.let { button ->
                if (isOutgoing && message.status == ChatMessage.MessageStatus.FAILED) {
                    button.visibility = View.VISIBLE
                    button.setOnClickListener {
                        onRetryMessage?.invoke(message)
                    }
                } else {
                    button.visibility = View.GONE
                    button.setOnClickListener(null)
                }
            }
        }

    }

    companion object {
        private const val VIEW_TYPE_INCOMING = 1
        private const val VIEW_TYPE_OUTGOING = 2
    }
}
