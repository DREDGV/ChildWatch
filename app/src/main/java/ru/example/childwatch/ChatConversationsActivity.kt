package ru.example.childwatch

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.childwatch.shared.chat.Conversation
import ru.childwatch.shared.chat.ConversationMember
import ru.childwatch.shared.chat.ConversationType
import ru.example.childwatch.chat.v2.ChatConversationListAdapter
import ru.example.childwatch.chat.v2.ChatV2Repository
import ru.example.childwatch.databinding.ActivityChatConversationsBinding
import ru.example.childwatch.network.WebSocketManager
import ru.example.childwatch.profile.ParentEffectiveContextProvider
import ru.example.childwatch.utils.SecureSettingsManager

class ChatConversationsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChatConversationsBinding
    private lateinit var repository: ChatV2Repository
    private lateinit var adapter: ChatConversationListAdapter
    private var cachedConversations: List<Conversation> = emptyList()
    private var refreshInProgress = false
    private val subscribedConversationIds = linkedSetOf<String>()
    private val chatV2MessageListener: (JSONObject) -> Unit = { payload ->
        if (payload.optString("conversationId") in subscribedConversationIds) refresh()
    }
    private val chatV2ReceiptListener: (JSONObject) -> Unit = { payload ->
        if (payload.optString("conversationId") in subscribedConversationIds) refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatConversationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val serverUrl = resolveServerUrl()
        if (serverUrl.isBlank()) {
            binding.statusText.setText(R.string.chat_v2_offline)
            binding.newDirectChatButton.isEnabled = false
            return
        }
        repository = ChatV2Repository.create(this, serverUrl)
        adapter = ChatConversationListAdapter(::openConversation)
        binding.conversationsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.conversationsRecyclerView.adapter = adapter
        binding.newDirectChatButton.setOnClickListener { chooseDirectChatMember() }
    }

    override fun onStart() {
        super.onStart()
        if (::repository.isInitialized) {
            WebSocketManager.addChatV2MessageListener(chatV2MessageListener)
            WebSocketManager.addChatV2ReceiptListener(chatV2ReceiptListener)
            refresh()
        }
    }

    override fun onStop() {
        subscribedConversationIds.toList().forEach(WebSocketManager::unsubscribeChatV2)
        subscribedConversationIds.clear()
        WebSocketManager.removeChatV2MessageListener(chatV2MessageListener)
        WebSocketManager.removeChatV2ReceiptListener(chatV2ReceiptListener)
        super.onStop()
    }

    private fun refresh() {
        if (refreshInProgress) return
        refreshInProgress = true
        lifecycleScope.launch {
            val local = repository.getCachedConversations()
            if (local.isNotEmpty()) {
                cachedConversations = local
                showConversations(local)
                updateRealtimeSubscriptions(local)
                binding.statusText.setText(R.string.chat_v2_ready)
            } else {
                binding.statusText.setText(R.string.chat_v2_syncing)
            }
            try {
                cachedConversations = repository.refreshConversations(
                    legacyChildDeviceId = resolveTargetChildDeviceId()
                )
                showConversations(cachedConversations)
                updateRealtimeSubscriptions(cachedConversations)
                repository.flushOutbox()
                binding.statusText.setText(R.string.chat_v2_ready)
            } catch (_: Exception) {
                if (cachedConversations.isEmpty()) {
                    cachedConversations = repository.getCachedConversations()
                    showConversations(cachedConversations)
                }
                binding.statusText.setText(R.string.chat_v2_offline)
            } finally {
                refreshInProgress = false
            }
        }
    }

    private fun showConversations(items: List<Conversation>) {
        adapter.submitList(items.sortedByDescending { it.updatedAt })
        binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.conversationsRecyclerView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateRealtimeSubscriptions(items: List<Conversation>) {
        val wanted = items.mapTo(linkedSetOf()) { it.conversationId }
        (subscribedConversationIds - wanted).forEach(WebSocketManager::unsubscribeChatV2)
        (wanted - subscribedConversationIds).forEach(WebSocketManager::subscribeChatV2)
        subscribedConversationIds.clear()
        subscribedConversationIds.addAll(wanted)
    }

    private fun chooseDirectChatMember() {
        val family = cachedConversations.firstOrNull { it.type == ConversationType.FAMILY }
        val candidates = family?.members.orEmpty()
            .filterNot(ConversationMember::isLocalUser)
            .distinctBy(ConversationMember::memberId)
            .sortedBy(ConversationMember::displayName)
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.chat_v2_no_members, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_v2_choose_member)
            .setItems(candidates.map { it.displayName }.toTypedArray()) { _, index ->
                lifecycleScope.launch {
                    try {
                        openConversation(repository.createDirect(candidates[index].memberId))
                    } catch (_: Exception) {
                        Toast.makeText(
                            this@ChatConversationsActivity,
                            R.string.chat_v2_offline,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openConversation(conversation: Conversation) {
        startActivity(Intent(this, ChatConversationV2Activity::class.java).apply {
            putExtra(ChatConversationV2Activity.EXTRA_CONVERSATION_ID, conversation.conversationId)
            putExtra(ChatConversationV2Activity.EXTRA_CONVERSATION_TITLE, conversation.title)
        })
    }

    private fun resolveServerUrl(): String =
        ParentEffectiveContextProvider.get(this).featureContext("chat")?.serverUrl
            ?.trim().orEmpty()
            .ifBlank { SecureSettingsManager(this).getServerUrl().trim() }

    private fun resolveTargetChildDeviceId(): String? =
        ParentEffectiveContextProvider.get(this).featureContext("chat")?.targetDeviceId
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: SecureSettingsManager(this).getChildDeviceId()?.trim()?.takeIf { it.isNotEmpty() }
}
