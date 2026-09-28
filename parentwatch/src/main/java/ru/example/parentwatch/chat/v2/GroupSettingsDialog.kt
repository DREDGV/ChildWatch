package ru.example.parentwatch.chat.v2

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.childwatch.shared.chat.ChatV2GroupSettingsResponse
import ru.childwatch.shared.chat.Conversation
import ru.childwatch.shared.chat.ConversationMember
import ru.childwatch.shared.chat.ConversationType
import ru.example.parentwatch.R
import ru.example.parentwatch.profile.FamilyAvatarRenderer

/**
 * Shared settings of a group conversation.
 *
 * A group belongs to its participants, so its name and picture are changed once
 * for everybody. Only the administrator may do it, and the administrator is the
 * member the server reports; everyone else sees the settings read-only, which is
 * why the dialog re-reads them instead of trusting the local cache.
 */
object GroupSettingsDialog {

    /**
     * Opens the dialog for [conversation].
     *
     * @param onChanged called after the server accepted a change, so the caller
     *        can refresh the list and the chat header
     */
    fun show(
        activity: Activity,
        scope: CoroutineScope,
        repository: ChatV2Repository,
        conversation: Conversation,
        onChanged: () -> Unit
    ) {
        scope.launch {
            val settings = repository.loadGroupSettings(conversation.conversationId)
            if (settings == null) {
                android.widget.Toast.makeText(
                    activity,
                    R.string.group_settings_unavailable,
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            activity.runOnUiThread {
                showLoaded(activity, scope, repository, conversation, settings, onChanged)
            }
        }
    }

    private fun showLoaded(
        activity: Activity,
        scope: CoroutineScope,
        repository: ChatV2Repository,
        conversation: Conversation,
        settings: ChatV2GroupSettingsResponse,
        onChanged: () -> Unit
    ) {
        val title = settings.title?.takeIf { it.isNotBlank() } ?: conversation.title
        // A family chat holds the whole family, so it has no membership of its own to
        // manage; only a group's membership is somebody's to choose.
        val isGroup = settings.type.equals("GROUP", ignoreCase = true)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (settings.canManage) {
            actions += activity.getString(R.string.group_action_rename) to {
                promptTitle(activity, scope, repository, conversation, title, onChanged)
            }
            actions += activity.getString(R.string.group_action_avatar) to {
                promptAvatar(
                    activity,
                    scope,
                    repository,
                    conversation,
                    settings.avatarKey,
                    onChanged
                )
            }
            if (isGroup) {
                actions += activity.getString(R.string.group_action_add_members) to {
                    promptAddMembers(
                        activity,
                        scope,
                        repository,
                        conversation,
                        settings,
                        onChanged
                    )
                }
                actions += activity.getString(R.string.group_action_remove_member) to {
                    promptRemoveMember(
                        activity,
                        scope,
                        repository,
                        conversation,
                        settings,
                        onChanged
                    )
                }
                actions += activity.getString(R.string.group_action_transfer_admin) to {
                    promptTransferAdmin(
                        activity,
                        scope,
                        repository,
                        conversation,
                        settings,
                        onChanged
                    )
                }
                // The administrator never leaves: the group would keep a name and a
                // membership nobody could change, so closing it is their way out.
                actions += activity.getString(R.string.group_action_close) to {
                    confirmClose(activity, scope, repository, conversation, onChanged)
                }
            }
        } else {
            actions += activity.getString(R.string.group_read_only) to {}
            // Leaving is the one thing a member may decide alone.
            if (isGroup) {
                actions += activity.getString(R.string.group_action_leave) to {
                    confirmLeave(activity, scope, repository, conversation, onChanged)
                }
            }
        }

        val builder = AlertDialog.Builder(activity).setTitle(title)
        if (isGroup) {
            administratorName(settings)?.let { administrator ->
                builder.setMessage(
                    activity.getString(R.string.group_admin_label, administrator)
                )
            }
        }
        builder
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The administrator as a person, or null when the server did not name one. */
    private fun administratorName(settings: ChatV2GroupSettingsResponse): String? {
        val adminMemberId = settings.adminMemberId ?: return null
        return settings.members.firstOrNull { it.memberId == adminMemberId }
            ?.displayName
            ?.takeIf { it.isNotBlank() }
    }

    private fun promptTitle(
        activity: Activity,
        scope: CoroutineScope,
        repository: ChatV2Repository,
        conversation: Conversation,
        current: String,
        onChanged: () -> Unit
    ) {
        val input = android.widget.EditText(activity).apply {
            setText(current)
            setSelection(text.length)
            hint = activity.getString(R.string.group_name_hint)
        }
        val container = LinearLayout(activity).apply {
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.group_action_rename)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString().orEmpty().trim()
                if (name.isEmpty() || name == current) return@setPositiveButton
                scope.launch {
                    val outcome = runCatching {
                        repository.renameGroup(conversation.conversationId, name)
                    }
                    val updated = outcome.getOrNull()
                    activity.runOnUiThread {
                        android.widget.Toast.makeText(
                            activity,
                            if (updated != null) {
                                activity.getString(R.string.group_settings_saved)
                            } else {
                                failureText(
                                    activity,
                                    outcome.exceptionOrNull(),
                                    R.string.group_settings_failed
                                )
                            },
                            if (updated != null) {
                                android.widget.Toast.LENGTH_SHORT
                            } else {
                                android.widget.Toast.LENGTH_LONG
                            }
                        ).show()
                    }
                    if (updated != null) onChanged()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptAvatar(
        activity: Activity,
        scope: CoroutineScope,
        repository: ChatV2Repository,
        conversation: Conversation,
        currentAvatarKey: String?,
        onChanged: () -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        var selected = currentAvatarKey
        val avatarValues = FamilyAvatarRenderer.selectableValues()

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = android.widget.HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        val views = mutableListOf<ShapeableImageView>()
        val size = (52 * density).toInt()
        val spacing = (8 * density).toInt()

        fun refresh() {
            val primary = ContextCompat.getColor(activity, R.color.cw_color_primary)
            val outline = ContextCompat.getColor(activity, R.color.cw_color_outline_variant)
            avatarValues.zip(views).forEach { (value, view) ->
                val isSelected = value == selected
                view.strokeColor = android.content.res.ColorStateList.valueOf(
                    if (isSelected) primary else outline
                )
                view.strokeWidth = (if (isSelected) 3f else 1f) * density
                view.alpha = if (isSelected) 1f else 0.7f
            }
        }

        avatarValues.forEachIndexed { index, value ->
            val view = ShapeableImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    if (index > 0) marginStart = spacing
                }
                shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCornerSizes(size / 2f)
                    .build()
                setOnClickListener {
                    selected = value
                    refresh()
                }
            }
            FamilyAvatarRenderer.bind(view, value)
            views += view
            row.addView(view)
        }
        refresh()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(
                android.widget.TextView(activity).apply {
                    text = activity.getString(R.string.group_avatar_hint)
                    setPadding(0, 0, 0, (8 * density).toInt())
                    visibility = View.VISIBLE
                }
            )
            addView(scroll)
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.group_action_avatar)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (selected == currentAvatarKey) return@setPositiveButton
                scope.launch {
                    val outcome = runCatching {
                        repository.updateGroupAvatar(conversation.conversationId, selected)
                    }
                    val updated = outcome.getOrNull()
                    activity.runOnUiThread {
                        android.widget.Toast.makeText(
                            activity,
                            if (updated != null) {
                                activity.getString(R.string.group_settings_saved)
                            } else {
                                failureText(
                                    activity,
                                    outcome.exceptionOrNull(),
                                    R.string.group_settings_failed
                                )
                            },
                            if (updated != null) {
                                android.widget.Toast.LENGTH_SHORT
                            } else {
                                android.widget.Toast.LENGTH_LONG
                            }
                        ).show()
                    }
                    if (updated != null) onChanged()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Offers the family members who are not in the group yet.
     *
     * A group can only ever hold family members — the server refuses anybody else —
     * so the list is taken from the family chat and everyone already in the group is
     * left out of it.
     */
    private fun promptAddMembers(
        activity: Activity,
        scope: CoroutineScope,
        repository: ChatV2Repository,
        conversation: Conversation,
        settings: ChatV2GroupSettingsResponse,
        onChanged: () -> Unit
    ) {
        scope.launch {
            val present = settings.members.mapTo(mutableSetOf()) { it.memberId }
            val candidates = repository.getCachedConversations()
                .firstOrNull { it.type == ConversationType.FAMILY }
                ?.members.orEmpty()
                .filterNot(ConversationMember::isLocalUser)
                .filterNot { it.memberId in present }
                .distinctBy(ConversationMember::memberId)
                .sortedBy(ConversationMember::displayName)

            activity.runOnUiThread {
                if (candidates.isEmpty()) {
                    android.widget.Toast.makeText(
                        activity,
                        R.string.group_members_empty,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }

                val checked = BooleanArray(candidates.size)
                AlertDialog.Builder(activity)
                    .setTitle(R.string.group_action_add_members)
                    .setMultiChoiceItems(
                        candidates.map { it.displayName }.toTypedArray(),
                        checked
                    ) { _, index, isChecked -> checked[index] = isChecked }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val chosen = candidates
                            .filterIndexed { index, _ -> checked[index] }
                            .map { it.memberId }
                        // Nothing ticked is not a refusal worth troubling the server
                        // with: it would name the same rule back.
                        if (chosen.isEmpty()) return@setPositiveButton
                        runGroupAction(
                            activity = activity,
                            scope = scope,
                            successMessage = R.string.group_members_added,
                            onSuccess = {
                                onChanged()
                                show(activity, scope, repository, conversation, onChanged)
                            }
                        ) { repository.addGroupMembers(conversation.conversationId, chosen) }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    /**
     * Takes one person out of the group.
     *
     * The administrator is not on the list: the server refuses removing oneself, and
     * that refusal exists because "remove me" is far more likely to be a slip in the
     * interface than a wish to lose the group.
     */
    private fun promptRemoveMember(
        activity: Activity,
        scope: CoroutineScope,
        repository: ChatV2Repository,
        conversation: Conversation,
        settings: ChatV2GroupSettingsResponse,
        onChanged: () -> Unit
    ) {
        val removable = settings.members
            .filter { it.memberId != settings.adminMemberId }
            .distinctBy { it.memberId }
        if (removable.isEmpty()) {
            android.widget.Toast.makeText(
                activity,
                R.string.group_no_other_members,
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.group_action_remove_member)
            .setItems(removable.map { it.displayName }.toTypedArray()) { _, index ->
                val target = removable[index]
                AlertDialog.Builder(activity)
                    .setTitle(R.string.group_action_remove_member)
                    .setMessage(
                        activity.getString(R.string.group_remove_confirm, target.displayName)
                    )
                    .setPositiveButton(R.string.group_action_remove_member) { _, _ ->
                        runGroupAction(
                            activity = activity,
                            scope = scope,
                            successMessage = R.string.group_member_removed,
                            onSuccess = {
                                onChanged()
                                show(activity, scope, repository, conversation, onChanged)
                            }
                        ) {
                            repository.removeGroupMember(conversation.conversationId, target.memberId)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Hands administration to another member of the group.
     *
     * This is how an administrator leaves the group without ending it: the group goes
     * on with somebody who can change its name and membership.
     */
    private fun promptTransferAdmin(
        activity: Activity,
        scope: CoroutineScope,
        repository: ChatV2Repository,
        conversation: Conversation,
        settings: ChatV2GroupSettingsResponse,
        onChanged: () -> Unit
    ) {
        val candidates = settings.members
            .filter { it.memberId != settings.adminMemberId }
            .distinctBy { it.memberId }
        if (candidates.isEmpty()) {
            android.widget.Toast.makeText(
                activity,
                R.string.group_no_other_members,
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.group_action_transfer_admin)
            .setItems(candidates.map { it.displayName }.toTypedArray()) { _, index ->
                val target = candidates[index]
                AlertDialog.Builder(activity)
                    .setTitle(R.string.group_action_transfer_admin)
                    .setMessage(
                        activity.getString(R.string.group_transfer_confirm, target.displayName)
                    )
                    .setPositiveButton(R.string.group_action_transfer_admin) { _, _ ->
                        runGroupAction(
                            activity = activity,
                            scope = scope,
                            successMessage = R.string.group_admin_transferred,
                            onSuccess = {
                                onChanged()
                                show(activity, scope, repository, conversation, onChanged)
                            }
                        ) {
                            repository.transferGroupAdmin(conversation.conversationId, target.memberId)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmLeave(
        activity: Activity,
        scope: CoroutineScope,
        repository: ChatV2Repository,
        conversation: Conversation,
        onChanged: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.group_action_leave)
            .setMessage(activity.getString(R.string.group_leave_confirm, conversation.title))
            .setPositiveButton(R.string.group_action_leave) { _, _ ->
                runGroupAction(
                    activity = activity,
                    scope = scope,
                    successMessage = R.string.group_left,
                    onSuccess = onChanged
                ) { repository.leaveGroup(conversation.conversationId) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClose(
        activity: Activity,
        scope: CoroutineScope,
        repository: ChatV2Repository,
        conversation: Conversation,
        onChanged: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.group_action_close)
            .setMessage(activity.getString(R.string.group_close_confirm, conversation.title))
            .setPositiveButton(R.string.group_action_close) { _, _ ->
                runGroupAction(
                    activity = activity,
                    scope = scope,
                    successMessage = R.string.group_closed,
                    onSuccess = onChanged
                ) { repository.closeGroup(conversation.conversationId) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Runs one change to the group's membership and says what came of it.
     *
     * The membership is shared, so the server's answer is what counts; a refusal is
     * told in words rather than repeated as a code, and [onSuccess] runs only when
     * the change really happened.
     */
    private fun runGroupAction(
        activity: Activity,
        scope: CoroutineScope,
        @StringRes successMessage: Int,
        onSuccess: () -> Unit,
        action: suspend () -> Boolean
    ) {
        scope.launch {
            val outcome = runCatching { action() }
            val succeeded = outcome.getOrNull() == true
            activity.runOnUiThread {
                android.widget.Toast.makeText(
                    activity,
                    if (succeeded) {
                        activity.getString(successMessage)
                    } else {
                        failureText(
                            activity,
                            outcome.exceptionOrNull(),
                            R.string.group_error_generic
                        )
                    },
                    if (succeeded) {
                        android.widget.Toast.LENGTH_SHORT
                    } else {
                        android.widget.Toast.LENGTH_LONG
                    }
                ).show()
            }
            if (succeeded) onSuccess()
        }
    }

    /**
     * What to say when a change did not happen.
     *
     * A refusal the server named is explained by its own code; an action that got no
     * answer at all can only say that the connection failed.
     */
    private fun failureText(activity: Activity, error: Throwable?, @StringRes noAnswer: Int): String =
        error?.let { activity.getString(GroupErrorMessages.forFailure(it)) }
            ?: activity.getString(noAnswer)
}
