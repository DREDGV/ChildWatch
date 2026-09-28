package ru.example.childwatch.chat.v2

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.childwatch.shared.chat.ChatV2GroupSettingsResponse
import ru.childwatch.shared.chat.ConversationMember
import ru.example.childwatch.R

/**
 * Everything that can be done to a group conversation.
 *
 * The server is the only authority on a group: it holds the name, the membership and
 * the administrator, and it refuses every change from anybody but the administrator.
 * That is why this screen re-reads the group's settings before each action and refreshes
 * the conversation list after it, rather than trusting what the device happens to have.
 *
 * Only a group reaches this screen. The family chat keeps its own settings, where a
 * rename renames the family itself; routing it here would quietly take that away.
 */
object GroupManagementDialog {

    /**
     * Opens the screen for one group.
     *
     * @param conversationId the group, whose cached copy the caller has just refreshed
     * @param familyMembers everybody in the family, the group's own members included,
     *        because a family member is the only kind of person who may join it
     * @param onRefresh refreshes the conversation list and waits for it, so the next
     *        reading of the group is the server's and not a stale cache
     */
    fun show(
        activity: Activity,
        scope: CoroutineScope,
        repository: ChatV2Repository,
        conversationId: String,
        familyMembers: List<ConversationMember>,
        onRefresh: suspend () -> Unit
    ) {
        scope.launch {
            val settings = repository.loadGroupSettings(conversationId)
            val group = repository.getCachedConversation(conversationId)
            if (settings == null || group == null) {
                GroupDialogs.toast(
                    activity,
                    activity.getString(R.string.group_settings_unavailable)
                )
                return@launch
            }
            activity.runOnUiThread {
                Screen(
                    activity = activity,
                    scope = scope,
                    repository = repository,
                    conversationId = conversationId,
                    familyMembers = familyMembers,
                    onRefresh = onRefresh
                ).showActions(settings, group.members)
            }
        }
    }

    /**
     * One open screen.
     *
     * The actions are re-listed against freshly loaded settings every time, so what is
     * offered always matches the group as the server last described it.
     */
    private class Screen(
        private val activity: Activity,
        private val scope: CoroutineScope,
        private val repository: ChatV2Repository,
        private val conversationId: String,
        private val familyMembers: List<ConversationMember>,
        private val onRefresh: suspend () -> Unit
    ) {
        /**
         * Lists what may be done, given the group as the server last described it.
         *
         * [members] is the membership of the cached conversation, which the caller has
         * just refreshed; the group's own answer carries the same people but also the
         * question of who may change them, and one shape for a member everywhere keeps
         * this screen and the chat header alike.
         */
        fun showActions(settings: ChatV2GroupSettingsResponse, members: List<ConversationMember>) {
            val canManage = settings.canManage
            // The identifier the server recognises this device by, and what it refuses
            // the administrator's own removal and hand-over by.
            val localMemberId = settings.actorMemberId.orEmpty()
            val others = members
                .filterNot { it.memberId == localMemberId }
                .distinctBy(ConversationMember::memberId)
                .sortedBy(ConversationMember::displayName)
            val removable = others.map { member ->
                if (member.memberId == settings.adminMemberId) {
                    activity.getString(R.string.group_admin_is, member.displayName)
                } else {
                    member.displayName
                }
            }
            val candidates = familyMembers
                .filterNot(ConversationMember::isLocalUser)
                .filterNot { familyMember -> members.any { it.memberId == familyMember.memberId } }
                .distinctBy(ConversationMember::memberId)
                .sortedBy(ConversationMember::displayName)
            val title = settings.title?.takeIf { it.isNotBlank() }
                ?: activity.getString(R.string.group_new_title)

            // The administrator's actions are listed first and leaving or closing comes
            // last; the positions are what [act] reads, so the two lists below and the
            // branch in [act] have to be changed together.
            val labels = mutableListOf<String>()
            if (canManage) {
                labels += activity.getString(R.string.group_action_rename)
                labels += activity.getString(R.string.group_action_avatar)
                labels += activity.getString(R.string.group_action_members_add)
                labels += activity.getString(R.string.group_action_member_remove)
                labels += activity.getString(R.string.group_action_admin_transfer)
                labels += activity.getString(R.string.group_action_close)
            } else {
                labels += activity.getString(R.string.group_action_leave)
            }

            MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setItems(labels.toTypedArray()) { _, which ->
                    act(
                        which = which,
                        canManage = canManage,
                        title = title,
                        settings = settings,
                        localMemberId = localMemberId,
                        others = others,
                        removable = removable,
                        candidates = candidates
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun act(
            which: Int,
            canManage: Boolean,
            title: String,
            settings: ChatV2GroupSettingsResponse,
            localMemberId: String,
            others: List<ConversationMember>,
            removable: List<String>,
            candidates: List<ConversationMember>
        ) {
            // An administrator manages the group; everybody else may only leave it,
            // which the server allows and nothing else here would.
            if (!canManage) {
                if (which == LEAVE) promptLeave(title, localMemberId) else promptClose(title)
                return
            }
            when (which) {
                RENAME -> GroupSettingsDialog.show(
                    activity = activity,
                    scope = scope,
                    repository = repository,
                    conversationId = conversationId,
                    contextTitle = settings.title.orEmpty(),
                    startWith = GroupSettingsDialog.Prompt.TITLE,
                    onChanged = { refreshAndReopen() }
                )
                AVATAR -> GroupSettingsDialog.show(
                    activity = activity,
                    scope = scope,
                    repository = repository,
                    conversationId = conversationId,
                    contextTitle = settings.title.orEmpty(),
                    startWith = GroupSettingsDialog.Prompt.AVATAR,
                    onChanged = { refreshAndReopen() }
                )
                ADD_MEMBERS -> promptAddMembers(candidates)
                REMOVE_MEMBER -> promptRemoveMember(title, others, removable)
                TRANSFER_ADMIN -> promptTransferAdmin(title, others)
                // The sixth entry is the administrator's only: closing the group.
                else -> promptClose(title)
            }
        }

        private fun promptAddMembers(candidates: List<ConversationMember>) {
            if (candidates.isEmpty()) {
                GroupDialogs.toast(activity, activity.getString(R.string.group_members_none_to_add))
                return
            }
            MemberPicker.pick(
                activity = activity,
                title = activity.getString(R.string.group_members_add_title),
                members = candidates,
                confirmLabel = activity.getString(R.string.group_action_members_add)
            ) { selected ->
                GroupDialogs.request(
                    activity = activity,
                    scope = scope,
                    onApplied = { refreshAndReopen() }
                ) {
                    repository.addGroupMembers(
                        conversationId,
                        selected.map(ConversationMember::memberId)
                    )
                }
            }
        }

        private fun promptRemoveMember(
            title: String,
            others: List<ConversationMember>,
            removable: List<String>
        ) {
            if (others.isEmpty()) {
                GroupDialogs.toast(
                    activity,
                    activity.getString(R.string.group_members_none_to_remove)
                )
                return
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.group_action_member_remove)
                .setItems(removable.toTypedArray()) { _, index ->
                    val member = others[index]
                    GroupDialogs.confirm(
                        activity = activity,
                        title = activity.getString(R.string.group_action_member_remove),
                        message = activity.getString(
                            R.string.group_member_remove_confirm,
                            member.displayName,
                            title
                        ),
                        confirmLabel = activity.getString(R.string.group_action_member_remove)
                    ) {
                        GroupDialogs.request(
                            activity = activity,
                            scope = scope,
                            onApplied = { refreshAndReopen() }
                        ) {
                            repository.removeGroupMember(conversationId, member.memberId)
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun promptTransferAdmin(title: String, others: List<ConversationMember>) {
            if (others.isEmpty()) {
                GroupDialogs.toast(
                    activity,
                    activity.getString(R.string.group_members_none_to_transfer)
                )
                return
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.group_action_admin_transfer)
                .setItems(others.map(ConversationMember::displayName).toTypedArray()) { _, index ->
                    val member = others[index]
                    GroupDialogs.confirm(
                        activity = activity,
                        title = activity.getString(R.string.group_action_admin_transfer),
                        message = activity.getString(
                            R.string.group_admin_transfer_confirm,
                            member.displayName,
                            title
                        ),
                        confirmLabel = activity.getString(R.string.group_action_admin_transfer)
                    ) {
                        GroupDialogs.request(
                            activity = activity,
                            scope = scope,
                            onApplied = { refreshAndReopen() }
                        ) {
                            repository.transferGroupAdmin(conversationId, member.memberId)
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /**
         * Leaving.
         *
         * The server refuses an administrator who tries to leave, because a group whose
         * administrator walked away would have a name and a membership nobody could
         * change. Asking anyway would turn a clear rule into an error message, so the
         * administrator is shown the rule and the two ways it allows instead.
         */
        private fun promptLeave(title: String, localMemberId: String) {
            if (localMemberId.isEmpty()) {
                GroupDialogs.confirm(
                    activity = activity,
                    title = activity.getString(R.string.group_action_leave),
                    message = activity.getString(R.string.group_admin_leave_blocked),
                    confirmLabel = activity.getString(R.string.group_action_close)
                ) { promptClose(title) }
                return
            }
            GroupDialogs.confirm(
                activity = activity,
                title = activity.getString(R.string.group_action_leave),
                message = activity.getString(R.string.group_leave_confirm, title),
                confirmLabel = activity.getString(R.string.group_action_leave)
            ) {
                GroupDialogs.request(
                    activity = activity,
                    scope = scope,
                    onApplied = { closeWith(R.string.group_left) }
                ) {
                    repository.leaveGroup(conversationId)
                }
            }
        }

        private fun promptClose(title: String) {
            GroupDialogs.confirm(
                activity = activity,
                title = activity.getString(R.string.group_action_close),
                message = activity.getString(R.string.group_close_confirm, title),
                confirmLabel = activity.getString(R.string.group_action_close)
            ) {
                GroupDialogs.request(
                    activity = activity,
                    scope = scope,
                    onApplied = { closeWith(R.string.group_closed) }
                ) {
                    repository.closeGroup(conversationId)
                }
            }
        }

        /** The group is gone from this device's list; the list is what the user sees. */
        private fun closeWith(messageRes: Int) {
            GroupDialogs.toast(activity, activity.getString(messageRes))
            scope.launch { onRefresh() }
        }

        /** Reads the group again after a change, then lists what is left to do. */
        private fun refreshAndReopen() {
            scope.launch {
                onRefresh()
                val settings = repository.loadGroupSettings(conversationId)
                val group = repository.getCachedConversation(conversationId)
                activity.runOnUiThread {
                    // The group may have been closed by the change — the server closes
                    // one that fewer than two people are left in — and there is then
                    // nothing left to manage.
                    if (settings != null && group != null) showActions(settings, group.members)
                }
            }
        }

        private companion object {
            /** The administrator's list, in the order it is built; the rest is "close". */
            const val RENAME = 0
            const val AVATAR = 1
            const val ADD_MEMBERS = 2
            const val REMOVE_MEMBER = 3
            const val TRANSFER_ADMIN = 4

            /** Everybody else's list, which is only "leave" and "close". */
            const val LEAVE = 0
        }
    }
}
