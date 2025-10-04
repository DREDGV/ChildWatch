package ru.example.childwatch.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ru.example.childwatch.R

/**
 * Adapter for chat messages RecyclerView
 */
class ChatAdapter(private val messages: List<ChatMessage>) : 
    RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {
    
    companion object {
        private const val VIEW_TYPE_CHILD = 1
        private const val VIEW_TYPE_PARENT = 2
    }
    
    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isFromChild()) VIEW_TYPE_CHILD else VIEW_TYPE_PARENT
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layoutId = when (viewType) {
            VIEW_TYPE_CHILD -> R.layout.item_message_child
            VIEW_TYPE_PARENT -> R.layout.item_message_parent
            else -> R.layout.item_message_child
        }
        
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return MessageViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.bind(message)
    }
    
    override fun getItemCount(): Int = messages.size
    
    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        private val timestampText: TextView = itemView.findViewById(R.id.timestampText)
        private val senderText: TextView? = itemView.findViewById(R.id.senderText)
        
        fun bind(message: ChatMessage) {
            messageText.text = message.text
            timestampText.text = message.getFormattedTime()
            senderText?.text = message.getSenderName()
            
            // Set different colors for child and parent messages
            val context = itemView.context
            if (message.isFromChild()) {
                messageText.setTextColor(ContextCompat.getColor(context, android.R.color.white))
                messageText.setBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_blue_dark))
            } else {
                messageText.setTextColor(ContextCompat.getColor(context, android.R.color.black))
                messageText.setBackgroundColor(ContextCompat.getColor(context, android.R.color.white))
            }
        }
    }
}
