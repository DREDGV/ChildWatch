package ru.example.parentwatch

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
import ru.example.parentwatch.chat.v2.ChatConversationListAdapter
import ru.example.parentwatch.chat.v2.ChatV2Repository
import ru.example.parentwatch.chat.v2.GroupSettingsDialog
import ru.example.parentwatch.databinding.ActivityChatConversationsBinding
import ru.example.parentwatch.network.WebSocketManager
import ru.example.parentwatch.session.ChildActiveSessionStore
import ru.example.parentwatch.session.ChildEffectiveContextProvider
import ru.example.parentwatch.utils.ServerUrlResolver

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
        adapter = ChatConversationListAdapter(
            onClick = ::openConversation,
            onEdit = ::showConversationActions
        )
        binding.conversationsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.conversationsRecyclerView.adapter = adapter
        binding.newDirectChatButton.setOnClickListener { chooseDirectChatMember() }
        // Returns to the home screen; the toolbar is built in this layout, so the
        // arrow needs its own listener.
        binding.backButton.setOnClickListener { finish() }
    }

    /**
     * Rename or remove a conversation.
     *
     * There was no way to correct a name or to get rid of an accidental
     * duplicate, so a wrongly created chat stayed in the list forever.
     */
    private fun showConversationActions(conversation: Conversation) {
        val isGroup = conversation.type != ConversationType.DIRECT
        val labels = mutableListOf<String>()
        // A group's name and picture are common to everyone, so they are managed
        // separately from the personal name this device may give the chat.
        if (isGroup) labels += getString(R.string.chat_action_group_settings)
        labels += getString(R.string.chat_action_rename)
        labels += getString(R.string.chat_action_remove)
        val actions = labels.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(conversation.title)
            .setItems(actions) { _, which ->
                when (actions[which]) {
                    getString(R.string.chat_action_group_settings) ->
                        GroupSettingsDialog.show(
                            activity = this,
                            scope = lifecycleScope,
                            repository = repository,
                            conversation = conversation
                        ) { refresh() }
                    getString(R.string.chat_action_rename) ->
                        promptRenameConversation(conversation)
                    else -> confirmRemoveConversation(conversation)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptRenameConversation(conversation: Conversation) {
        val input = android.widget.EditText(this).apply {
            setText(conversation.title)
            hint = getString(R.string.chat_rename_hint)
            setSelection(text.length)
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.chat_action_rename)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    repository.renameConversation(conversation.conversationId, input.text?.toString())
                    refresh()
                }
            }
            .setNeutralButton(R.string.chat_rename_reset) { _, _ ->
                lifecycleScope.launch {
                    // Clearing the local name restores the title from the server.
                    repository.renameConversation(conversation.conversationId, null)
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemoveConversation(conversation: Conversation) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.chat_action_remove)
            .setMessage(getString(R.string.chat_remove_confirm, conversation.title))
            .setPositiveButton(R.string.chat_action_remove) { _, _ ->
                lifecycleScope.launch {
                    repository.removeConversationFromList(conversation.conversationId)
                    Toast.makeText(
                        this@ChatConversationsActivity,
                        R.string.chat_removed,
                        Toast.LENGTH_SHORT
                    ).show()
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
                    legacyChildDeviceId = resolveOwnChildDeviceId()
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
        ChildEffectiveContextProvider.get(this).featureContext("chat")?.serverUrl
            ?.trim().orEmpty()
            .ifBlank { ServerUrlResolver.getServerUrl(this).orEmpty().trim() }

    private fun resolveOwnChildDeviceId(): String? =
        ChildEffectiveContextProvider.get(this).current()?.selfDeviceId
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: ChildActiveSessionStore(this).resolveCurrentChildId()
                .trim().takeIf { it.isNotEmpty() }
}
