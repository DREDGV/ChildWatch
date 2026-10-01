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
import ru.example.childwatch.chat.v2.GroupCreationDialog
import ru.example.childwatch.chat.v2.GroupManagementDialog
import ru.example.childwatch.chat.v2.GroupSettingsDialog
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
        supportActionBar?.hide()
        binding.backButton.setOnClickListener { finish() }

        val serverUrl = resolveServerUrl()
        if (serverUrl.isBlank()) {
            binding.statusText.setText(R.string.chat_v2_offline)
            binding.newDirectChatButton.isEnabled = false
            binding.newGroupChatButton.isEnabled = false
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
        binding.newGroupChatButton.setOnClickListener { createGroup() }
        // Returns to the home screen; the toolbar is built in this layout, so the
        // arrow needs its own listener.
        binding.backButton.setOnClickListener { finish() }
    }

    /**
     * Rename or remove a conversation.
     *
     * There was no way to correct a name or to get rid of an accidental
     * duplicate, so a wrongly created chat stayed in the list forever.
     *
     * The list is brought up to date first: a group's membership and administrator
     * live on the server, so a screen built on the cache could offer a person the
     * group already holds or a name that has since been changed.
     */
    private fun showConversationActions(conversation: Conversation) {
        lifecycleScope.launch {
            refreshAwaitable()
            val current = cachedConversations
                .firstOrNull { it.conversationId == conversation.conversationId }
                ?: conversation
            val labels = mutableListOf<String>()
            // The family chat and a group are managed differently: the family's name
            // and picture belong to the family, while a group is its own conversation
            // with a membership that only its administrator may change.
            if (current.type != ConversationType.DIRECT) {
                labels += getString(R.string.chat_action_group_settings)
            }
            labels += getString(R.string.chat_action_rename)
            labels += getString(R.string.chat_action_remove)
            val actions = labels.toTypedArray()

            androidx.appcompat.app.AlertDialog.Builder(this@ChatConversationsActivity)
                .setTitle(current.title)
                .setItems(actions) { _, which ->
                    when {
                        actions[which] == getString(R.string.chat_action_group_settings) ->
                            openGroupSettings(current)
                        actions[which] == getString(R.string.chat_action_rename) ->
                            promptRenameConversation(current)
                        else -> confirmRemoveConversation(current)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /**
     * Sends a conversation to the settings that own it.
     *
     * A group goes to its own screen, which holds its membership as well as its name;
     * the family chat keeps the family's settings, where a rename renames the family.
     */
    private fun openGroupSettings(conversation: Conversation) {
        if (conversation.type == ConversationType.GROUP) {
            GroupManagementDialog.show(
                activity = this,
                scope = lifecycleScope,
                repository = repository,
                conversationId = conversation.conversationId,
                familyMembers = familyMembers(),
                onRefresh = { refreshAwaitable() }
            )
            return
        }
        GroupSettingsDialog.show(
            activity = this,
            scope = lifecycleScope,
            repository = repository,
            conversationId = conversation.conversationId,
            contextTitle = conversation.title
        ) { refresh() }
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
            try {
                refreshNow()
            } finally {
                refreshInProgress = false
            }
        }
    }

    /**
     * The same refresh, waited for.
     *
     * A screen about a group has to read the group after the server has been asked
     * about it, so its caller cannot simply fire the refresh and continue with
     * whatever the cache still holds.
     */
    private suspend fun refreshAwaitable() {
        if (refreshInProgress) return
        refreshInProgress = true
        try {
            refreshNow()
        } finally {
            refreshInProgress = false
        }
    }

    private suspend fun refreshNow() {
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
        val candidates = familyMembers()
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

    /**
     * Starts a new group: a name and the family members who are in it.
     *
     * A group can only be made of the family, so the people on offer are the family
     * chat's own members, and the list is refreshed first so somebody who has just
     * joined it can be chosen.
     */
    private fun createGroup() {
        lifecycleScope.launch {
            refreshAwaitable()
            val candidates = familyMembers()
            if (candidates.none { !it.isLocalUser }) {
                Toast.makeText(this@ChatConversationsActivity, R.string.chat_v2_no_members, Toast.LENGTH_SHORT)
                    .show()
                return@launch
            }
            GroupCreationDialog.show(
                activity = this@ChatConversationsActivity,
                scope = lifecycleScope,
                repository = repository,
                candidates = candidates
            ) { conversationId ->
                val created = cachedConversations.firstOrNull { it.conversationId == conversationId }
                openConversationById(conversationId, created?.title ?: getString(R.string.group_new_title))
            }
        }
    }

    /** Everybody the family chat names, which is who a conversation may be made of. */
    private fun familyMembers(): List<ConversationMember> =
        cachedConversations.firstOrNull { it.type == ConversationType.FAMILY }?.members.orEmpty()

    private fun openConversation(conversation: Conversation) =
        openConversationById(conversation.conversationId, conversation.title)

    private fun openConversationById(conversationId: String, title: String) {
        startActivity(Intent(this, ChatConversationV2Activity::class.java).apply {
            putExtra(ChatConversationV2Activity.EXTRA_CONVERSATION_ID, conversationId)
            putExtra(ChatConversationV2Activity.EXTRA_CONVERSATION_TITLE, title)
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
