package ru.example.parentwatch.chat.v2

import android.app.Activity
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.childwatch.shared.chat.ChatV2GroupSettingsResponse
import ru.childwatch.shared.chat.Conversation
import ru.childwatch.shared.chat.ConversationMember
import ru.childwatch.shared.chat.ConversationMemberRole
import ru.childwatch.shared.chat.ConversationType
import ru.example.parentwatch.R
import ru.example.parentwatch.profile.FamilyAvatarRenderer

/**
 * Everything that can be done to a group conversation.
 *
 * The server is the only authority on a group: it holds the name, the membership and
 * the administrator, and it refuses every change from anybody but the administrator.
 * That is why this screen re-reads the group before each action and asks the caller to
 * refresh its own copy afterwards, rather than trusting what the device happens to hold.
 *
 * The composition comes first and the actions after it, in the order of what they do to
 * the group: its name, then its people, then the administrator, and only at the end the
 * two ways of being done with it. What the reader may not do is not shown at all, so a
 * member is never offered a button whose only possible answer is "you may not" — the
 * administrator is never offered "leave", because the server refuses it and the group
 * would be left with a name and a membership nobody could change.
 *
 * A group has no picture of its own — the server refuses every attempt to give it one —
 * so that action is not offered here. A group the size of a family is taller than a
 * dialog, which is why the content is measured and bounded instead of left to run off
 * the bottom of the screen with the buttons.
 */
object GroupSettingsDialog {

    /**
     * Opens the screen for one conversation.
     *
     * @param onRefreshed called after the server accepted a change, so the caller can
     *        read the conversation again and write its own header from the answer
     */
    fun show(
        activity: Activity,
        scope: CoroutineScope,
        repository: ChatV2Repository,
        conversation: Conversation,
        onSettingsLoaded: (ChatV2GroupSettingsResponse) -> Unit = {},
        onChanged: () -> Unit
    ) {
        scope.launch {
            val settings = repository.loadGroupSettings(conversation.conversationId)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (settings == null) {
                    // The group could not be read: a connection problem, and one that
                    // says nothing about whether the group still exists.
                    android.widget.Toast.makeText(
                        activity,
                        R.string.group_settings_unavailable,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                onSettingsLoaded(settings)
                val screen = SettingsScreen(
                    activity = activity,
                    scope = scope,
                    repository = repository,
                    conversation = conversation,
                    onSettingsLoaded = onSettingsLoaded,
                    onChanged = onChanged
                )
                screen.show(settings)
            }
        }
    }

    /**
     * The screen itself.
     *
     * One screen at a time: opening the settings again from the conversation list while
     * they are already open on this group would leave two copies describing the same
     * group, and only one of them would follow a change. The newest copy replaces the
     * oldest rather than joining it.
     */
    private class SettingsScreen(
        private val activity: Activity,
        private val scope: CoroutineScope,
        private val repository: ChatV2Repository,
        private val conversation: Conversation,
        private val onSettingsLoaded: (ChatV2GroupSettingsResponse) -> Unit,
        private val onChanged: () -> Unit
    ) {
        private var dialog: AlertDialog? = null
        private var closed = false

        /** Only the newest answer may draw, so a slow one cannot undo a fresh one. */
        private var requestToken = 0

        fun show(settings: ChatV2GroupSettingsResponse) {
            if (closed || activity.isFinishing || activity.isDestroyed) return
            dialog?.setOnDismissListener(null)
            if (dialog?.isShowing == true) dialog?.dismiss()
            val isGroup = settings.type.equals(ConversationType.GROUP.name, ignoreCase = true)
            val title = settings.title?.takeIf { it.isNotBlank() } ?: conversation.title
            val content = column().apply {
                setPadding(dp(24), dp(4), dp(24), dp(12))
                if (isGroup) {
                    addGroupDetails(this, settings)
                } else {
                    // A family chat shares its name and picture with everybody too, but
                    // it has no membership to manage: everybody in the family is in it.
                    addView(metaLine(activity.getString(R.string.group_family_note)))
                    addView(sectionLabel(activity.getString(R.string.group_settings_actions)))
                    addView(
                        action(activity.getString(R.string.group_action_rename)) {
                            promptTitle(title)
                        }
                    )
                    addView(
                        action(activity.getString(R.string.group_action_avatar)) {
                            promptAvatar(settings.avatarKey)
                        }
                    )
                }
            }
            dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(title.ifBlank { activity.getString(R.string.group_settings_title) })
                .setView(scrollable(content))
                .setNegativeButton(R.string.group_settings_close, null)
                .create()
                .also {
                    it.setOnDismissListener { closed = true; requestToken++ }
                    it.show()
                }
        }

        /** The composition of a group: who administers it, how many, and who. */
        private fun addGroupDetails(parent: LinearLayout, settings: ChatV2GroupSettingsResponse) {
            val members = settings.members
            val administrator = members.firstOrNull { it.memberId == settings.adminMemberId }
            parent.addView(
                metaLine(
                    activity.getString(
                        R.string.group_admin_line,
                        administrator?.displayName?.takeIf { it.isNotBlank() }
                            ?: activity.getString(R.string.group_member_unknown)
                    ),
                    bold = true
                )
            )
            parent.addView(
                metaLine(
                    activity.resources.getQuantityString(
                        R.plurals.group_member_count,
                        members.size,
                        members.size
                    )
                )
            )
            parent.addView(spacer(dp(10)))
            parent.addView(sectionLabel(activity.getString(R.string.group_settings_members)))
            if (members.isEmpty()) {
                parent.addView(metaLine(activity.getString(R.string.group_members_unknown)))
            }
            members
                .distinctBy { it.memberId }
                .sortedBy { it.displayName.orEmpty() }
                .forEach { member -> parent.addView(memberRow(member, settings)) }

            parent.addView(spacer(dp(8)))
            parent.addView(divider())
            parent.addView(spacer(dp(8)))
            parent.addView(sectionLabel(activity.getString(R.string.group_settings_actions)))

            if (!settings.canManage) {
                // Why there are hardly any buttons is said before the one there is, so
                // the screen never looks broken to somebody who may only leave.
                parent.addView(note(activity.getString(R.string.group_read_only)))
                parent.addView(
                    action(
                        label = activity.getString(R.string.group_action_leave),
                        destructive = true
                    ) { confirmLeave(resolvedTitle(settings)) }
                )
                return
            }

            // In the order of what they do to the group: its name, then its people,
            // then who administers it, and last the way of ending it.
            parent.addView(
                action(activity.getString(R.string.group_action_rename)) {
                    promptTitle(resolvedTitle(settings))
                }
            )
            parent.addView(
                action(activity.getString(R.string.group_action_add_members)) {
                    promptAddMembers()
                }
            )
            // Neither this device's own member nor the administrator is a candidate for
            // the two actions that name a person: nobody removes themselves, removes the
            // administrator, or hands the group to the person who already holds it. The
            // server refuses each of those, and an action whose only answer is "you may
            // not" is not offered at all.
            val others = settings.members
                .filterNot {
                    it.memberId == settings.actorMemberId ||
                        it.memberId == settings.adminMemberId
                }
                .distinctBy { it.memberId }
                .sortedBy { it.displayName.orEmpty() }
            parent.addView(
                action(activity.getString(R.string.group_action_remove_member)) {
                    promptRemoveMember(settings, others)
                }
            )
            parent.addView(
                action(activity.getString(R.string.group_action_transfer_admin)) {
                    promptTransferAdmin(settings, others)
                }
            )
            parent.addView(spacer(dp(8)))
            parent.addView(divider())
            parent.addView(
                action(
                    label = activity.getString(R.string.group_action_close),
                    destructive = true
                ) { confirmClose(resolvedTitle(settings)) }
            )
        }

        /**
         * One person of the group: their name, their role, and what they are here.
         *
         * The role and the marks are what make this more than a list of names — they are
         * how a reader finds the administrator and themselves in a group of five.
         */
        private fun memberRow(
            member: ru.childwatch.shared.chat.ChatV2MemberDto,
            settings: ChatV2GroupSettingsResponse
        ): View {
            val marks = mutableListOf(activity.getString(roleLabel(member.role)))
            if (member.memberId == settings.adminMemberId) {
                marks += activity.getString(R.string.group_member_admin_mark)
            }
            if (member.memberId == settings.actorMemberId) {
                marks += activity.getString(R.string.group_member_you_mark)
            }
            return column().apply {
                setPadding(0, dp(6), 0, dp(6))
                addView(
                    body(
                        member.displayName?.takeIf { it.isNotBlank() }
                            ?: activity.getString(R.string.group_member_unknown),
                        R.color.cw_color_on_surface
                    )
                )
                addView(
                    body(
                        marks.joinToString(
                            activity.getString(R.string.group_member_marks_join)
                        ),
                        R.color.cw_color_on_surface_variant
                    )
                )
            }
        }

        /**
         * Asks for a new name, and answers a refusal under the field.
         *
         * The field counts up to the server's limit instead of cutting what is typed:
         * a silently shortened name would be saved as something nobody asked for.
         * Saving without a word would leave the screen looking exactly as it did
         * before, which reads as "nothing happened" rather than "the name is missing".
         */
        private fun promptTitle(current: String) {
            val input = android.widget.EditText(activity).apply {
                setText(current)
                setSelection(text.length)
                hint = activity.getString(R.string.group_rename_hint)
            }
            val field = TextInputLayout(activity).apply {
                hint = activity.getString(R.string.group_rename_hint)
                setCounterEnabled(true)
                counterMaxLength = GroupName.MAX_LENGTH
                addView(input)
            }
            val error = errorView()
            val container = column().apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(field)
                addView(error)
            }

            val prompt = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.group_action_rename)
                .setView(container)
                .setPositiveButton(R.string.group_settings_save, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

            /** Set while the request is in flight, so a second press does not repeat it. */
            var saving = false

            fun refuseInPlace(message: String, fieldError: Boolean) {
                if (fieldError) {
                    field.error = message
                } else {
                    error.setTextColor(
                        ContextCompat.getColor(activity, R.color.cw_color_error)
                    )
                    error.text = message
                    error.visibility = View.VISIBLE
                }
            }

            prompt.setOnShowListener {
                prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (saving) return@setOnClickListener
                    field.error = null
                    error.visibility = View.GONE
                    val name = input.text?.toString().orEmpty().trim()
                    val problem = GroupName.problem(name)
                    when {
                        problem != null -> field.error = activity.getString(problem)
                        name == current -> {
                            // Saving the same name changes nothing, so it is not sent;
                            // but a button that appears to do nothing is what this screen
                            // exists to stop, so the field says why.
                            error.setTextColor(
                                ContextCompat.getColor(
                                    activity,
                                    R.color.cw_color_on_surface_variant
                                )
                            )
                            error.setText(R.string.group_name_unchanged)
                            error.visibility = View.VISIBLE
                        }
                        else -> {
                            saving = true
                            prompt.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                            scope.launch {
                                val outcome = runCatching {
                                    repository.renameGroup(conversation.conversationId, name)
                                }
                                activity.runOnUiThread {
                                    saving = false
                                    prompt.getButton(AlertDialog.BUTTON_POSITIVE)
                                        ?.let { button -> button.isEnabled = true }
                                    val refusal = outcome.exceptionOrNull()
                                    if (refusal == null) {
                                        prompt.dismiss()
                                        announce(R.string.group_settings_saved)
                                        reload()
                                    } else {
                                        // A refusal about the name belongs under the
                                        // name; everything else belongs to the screen,
                                        // so "you may not change this group" is not
                                        // shown as a complaint about the typing.
                                        refuseInPlace(
                                            activity.getString(
                                                GroupErrorMessages.forFailure(refusal)
                                            ),
                                            fieldError = GroupErrorMessages.isAboutTitle(refusal)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            prompt.show()
        }

        /**
         * Offers the family members who are not in the group yet.
         *
         * A group can only ever hold family members — the server refuses anybody else —
         * so the list is taken from the family conversation and everyone already in the
         * group is left out of it. With nobody there to add the screen says so instead
         * of opening an empty list.
         */
        private fun promptAddMembers() {
            val token = ++requestToken
            scope.launch {
                val settings = repository.loadGroupSettings(conversation.conversationId)
                val refreshed = try {
                    settings?.familyId?.takeIf { it.isNotBlank() }?.let { familyId ->
                        kotlinx.coroutines.withTimeoutOrNull(15_000L) { repository.loadFamilyMembers(familyId) }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                if (closed || token != requestToken || activity.isFinishing || activity.isDestroyed) return@launch
                if (settings == null || refreshed == null) {
                    GroupDialogs.toast(activity, activity.getString(R.string.group_settings_unavailable))
                    return@launch
                }
                if (!settings.canManage) {
                    onSettingsLoaded(settings)
                    show(settings)
                    GroupDialogs.toast(activity, activity.getString(R.string.group_read_only))
                    return@launch
                }
                val present = settings.members.mapTo(mutableSetOf()) { it.memberId }
                val candidates = refreshed
                    .filterNot { it.memberId == settings.actorMemberId || it.memberId in present }
                    .distinctBy { it.memberId }
                    .sortedBy { it.displayName }

                activity.runOnUiThread {
                    if (closed || token != requestToken || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if (candidates.isEmpty()) {
                        GroupDialogs.toast(activity, activity.getString(R.string.group_members_empty))
                        return@runOnUiThread
                    }
                    MemberPicker.pick(
                        activity = activity,
                        title = activity.getString(R.string.group_members_add_title),
                        members = candidates,
                        confirmLabel = activity.getString(R.string.group_picker_confirm)
                    ) { selected ->
                        GroupDialogs.request(
                            activity = activity,
                            scope = scope,
                            onApplied = {
                                // Named, so the answer says who joined and not merely
                                // that something happened.
                                announce(
                                    R.string.group_members_added,
                                    selected.joinToString(", ") { it.displayName }
                                )
                                reload()
                            },
                            onRefused = { message -> GroupDialogs.toast(activity, activity.getString(message)) }
                        ) {
                            repository.addGroupMembers(
                                conversation.conversationId,
                                selected.map(ConversationMember::memberId)
                            )
                        }
                    }
                }
            }
        }

        private fun promptRemoveMember(
            settings: ChatV2GroupSettingsResponse,
            candidates: List<ru.childwatch.shared.chat.ChatV2MemberDto>
        ) {
            if (candidates.isEmpty()) {
                GroupDialogs.toast(activity, activity.getString(R.string.group_no_other_members))
                return
            }
            val labels = candidates.map(::displayNameOf)
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.group_action_remove_member)
                .setItems(labels.toTypedArray()) { _, index ->
                    val target = candidates[index]
                    GroupDialogs.confirm(
                        activity = activity,
                        title = R.string.group_action_remove_member,
                        message = activity.getString(
                            R.string.group_remove_confirm,
                            displayNameOf(target),
                            resolvedTitle(settings)
                        ),
                        confirmLabel = R.string.group_action_remove_member
                    ) {
                        GroupDialogs.request(
                            activity = activity,
                            scope = scope,
                            onApplied = {
                                announce(R.string.group_member_removed, displayNameOf(target))
                                reload()
                            },
                            onRefused = { message -> GroupDialogs.toast(activity, activity.getString(message)) }
                        ) {
                            repository.removeGroupMember(
                                conversation.conversationId,
                                target.memberId
                            )
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun promptTransferAdmin(
            settings: ChatV2GroupSettingsResponse,
            candidates: List<ru.childwatch.shared.chat.ChatV2MemberDto>
        ) {
            if (candidates.isEmpty()) {
                GroupDialogs.toast(activity, activity.getString(R.string.group_no_other_members))
                return
            }
            val labels = candidates.map(::displayNameOf)
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.group_action_transfer_admin)
                .setItems(labels.toTypedArray()) { _, index ->
                    val target = candidates[index]
                    GroupDialogs.confirm(
                        activity = activity,
                        title = R.string.group_action_transfer_admin,
                        message = activity.getString(
                            R.string.group_transfer_confirm,
                            displayNameOf(target),
                            resolvedTitle(settings)
                        ),
                        confirmLabel = R.string.group_action_transfer_admin
                    ) {
                        GroupDialogs.request(
                            activity = activity,
                            scope = scope,
                            onApplied = {
                                announce(R.string.group_admin_transferred, displayNameOf(target))
                                reload()
                            },
                            onRefused = { message -> GroupDialogs.toast(activity, activity.getString(message)) }
                        ) {
                            repository.transferGroupAdmin(
                                conversation.conversationId,
                                target.memberId
                            )
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /**
         * Leaving.
         *
         * Only reached by a member who does not administer the group: an administrator
         * who walked away would leave the group with a name and a membership nobody
         * could change, which is why the server refuses it and why this screen never
         * offers it. The two ways out an administrator has are the ones above.
         */
        private fun confirmLeave(title: String) {
            GroupDialogs.confirm(
                activity = activity,
                title = R.string.group_action_leave,
                message = activity.getString(R.string.group_leave_confirm, title),
                confirmLabel = R.string.group_action_leave
            ) {
                GroupDialogs.request(
                    activity = activity,
                    scope = scope,
                    // The group is gone from this device's side, so a screen about it
                    // would only be a lie.
                    onApplied = { closeWith(R.string.group_left) },
                    onRefused = { message -> GroupDialogs.toast(activity, activity.getString(message)) }
                ) {
                    repository.leaveGroup(conversation.conversationId)
                }
            }
        }

        private fun confirmClose(title: String) {
            GroupDialogs.confirm(
                activity = activity,
                title = R.string.group_action_close,
                message = activity.getString(R.string.group_close_confirm, title),
                confirmLabel = R.string.group_action_close
            ) {
                GroupDialogs.request(
                    activity = activity,
                    scope = scope,
                    onApplied = { closeWith(R.string.group_closed) },
                    onRefused = { message -> GroupDialogs.toast(activity, activity.getString(message)) }
                ) {
                    repository.closeGroup(conversation.conversationId)
                }
            }
        }

        /**
         * Sets the shared picture of a conversation.
         *
         * Kept for the family chat, whose picture this really changes. A group has no
         * picture of its own and the server refuses every attempt to set one, so the
         * action is not offered for a group at all: an action that can never work is
         * not an action.
         */
        private fun promptAvatar(currentAvatarKey: String?) {
            val avatarValues = FamilyAvatarRenderer.selectableValues()
            var selected = currentAvatarKey

            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val scroll = android.widget.HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }
            val views = mutableListOf<ShapeableImageView>()
            val size = dp(52)

            fun refresh() {
                val primary = ContextCompat.getColor(activity, R.color.cw_color_primary)
                val outline = ContextCompat.getColor(activity, R.color.cw_color_outline_variant)
                avatarValues.zip(views).forEach { (value, view) ->
                    val isSelected = value == selected
                    view.strokeColor = ColorStateList.valueOf(if (isSelected) primary else outline)
                    view.strokeWidth = (if (isSelected) 3f else 1f) *
                        activity.resources.displayMetrics.density
                    view.alpha = if (isSelected) 1f else 0.7f
                }
            }

            avatarValues.forEachIndexed { index, value ->
                val view = ShapeableImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        if (index > 0) marginStart = dp(8)
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

            val error = errorView()
            val container = column().apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(metaLine(activity.getString(R.string.group_avatar_hint)))
                addView(scroll)
                addView(error)
            }

            val prompt = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.group_action_avatar)
                .setView(container)
                .setPositiveButton(R.string.group_settings_save, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

            /** Set while the request is in flight, so a second press does not repeat it. */
            var saving = false

            prompt.setOnShowListener {
                prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (saving) return@setOnClickListener
                    if (selected == currentAvatarKey) {
                        // Choosing the picture that is already there changes nothing, and
                        // the server would be asked for nothing.
                        prompt.dismiss()
                        return@setOnClickListener
                    }
                    saving = true
                    prompt.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    scope.launch {
                        val outcome = runCatching {
                            repository.updateGroupAvatar(conversation.conversationId, selected)
                        }
                        activity.runOnUiThread {
                            saving = false
                            prompt.getButton(AlertDialog.BUTTON_POSITIVE)
                                ?.let { button -> button.isEnabled = true }
                            val refusal = outcome.exceptionOrNull()
                            if (refusal == null) {
                                prompt.dismiss()
                                announce(R.string.group_settings_saved)
                                reload()
                            } else {
                                error.setTextColor(
                                    ContextCompat.getColor(activity, R.color.cw_color_error)
                                )
                                error.text = activity.getString(
                                    GroupErrorMessages.forFailure(refusal)
                                )
                                error.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }
            prompt.show()
        }

        /** The group is gone from this device's list; the list is what the person sees. */
        private fun closeWith(@StringRes message: Int) {
            dialog?.dismiss()
            GroupDialogs.toast(activity, activity.getString(message))
            onChanged()
        }

        /**
         * Reads the group again and draws what is left of it.
         *
         * The membership is shared: somebody else may have changed the group while this
         * screen was open, and the name has just changed under it. The last answer wins,
         * so a slow reply cannot put an older group back on screen.
         */
        private fun reload() {
            val token = ++requestToken
            scope.launch {
                onChanged()
                if (closed || token != requestToken || activity.isFinishing || activity.isDestroyed) return@launch
                val settings = repository.loadGroupSettings(conversation.conversationId)
                activity.runOnUiThread {
                    if (closed || token != requestToken || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if (settings == null) {
                        GroupDialogs.toast(activity, activity.getString(R.string.group_settings_unavailable))
                        return@runOnUiThread
                    }
                    onSettingsLoaded(settings)
                    show(settings)
                }
            }
        }

        /**
         * Says what came of an action, naming the thing that changed.
         *
         * Said rather than shown in this window because the window is redrawn from the
         * server's fresh answer straight afterwards, and a line written into it would be
         * thrown away before it could be read.
         */
        private fun announce(@StringRes message: Int, vararg formatArgs: String) {
            GroupDialogs.toast(activity, activity.getString(message, *formatArgs))
        }

        /** The group's name as the server last described it. */
        private fun resolvedTitle(settings: ChatV2GroupSettingsResponse): String =
            settings.title?.takeIf { it.isNotBlank() } ?: conversation.title

        private fun displayNameOf(member: ru.childwatch.shared.chat.ChatV2MemberDto): String =
            member.displayName?.takeIf { it.isNotBlank() }
                ?: activity.getString(R.string.group_member_unknown)

        @StringRes
        private fun roleLabel(role: String?): Int = when (role?.uppercase()) {
            "PARENT" -> R.string.group_role_parent
            "GUARDIAN" -> R.string.group_role_guardian
            else -> R.string.group_role_child
        }

        // The pieces the screen is made of. They are built rather than laid out in XML
        // because the membership is a list of unknown length, and each piece is one of
        // the same material components the rest of the application already uses.

        private fun column(): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        private fun metaLine(text: String, bold: Boolean = false): TextView = TextView(activity).apply {
            this.text = text
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(ContextCompat.getColor(activity, R.color.cw_color_on_surface_variant))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        private fun body(text: String, colorRes: Int): TextView = TextView(activity).apply {
            this.text = text
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(ContextCompat.getColor(activity, colorRes))
        }

        private fun sectionLabel(text: String): TextView = TextView(activity).apply {
            this.text = text.uppercase()
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(ContextCompat.getColor(activity, R.color.cw_color_on_surface_variant))
            setPadding(0, dp(12), 0, dp(2))
        }

        /** Why this screen offers so little, said plainly, without naming a role. */
        private fun note(text: String): TextView = TextView(activity).apply {
            this.text = text
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Material3_BodySmall)
            setTextColor(ContextCompat.getColor(activity, R.color.cw_color_on_surface_variant))
            setPadding(0, 0, 0, dp(8))
        }

        /** A sentence in the place a mistake belongs, in the colour mistakes are shown in. */
        private fun errorView(): TextView = TextView(activity).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_ChildWatch_TextInput_Error)
            visibility = View.GONE
        }

        private fun action(
            label: String,
            destructive: Boolean = false,
            onClick: () -> Unit
        ): MaterialButton {
            val fill = ContextCompat.getColor(
                activity,
                if (destructive) R.color.cw_color_error else android.R.color.transparent
            )
            val stroke = ContextCompat.getColor(
                activity,
                if (destructive) R.color.cw_color_error else R.color.cw_color_primary
            )
            val textColor = if (destructive) R.color.cw_color_error else R.color.cw_color_primary
            val background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 16f * activity.resources.displayMetrics.density
                setColor(fill)
                setStroke(dp(1).coerceAtLeast(1), stroke)
            }
            return MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonStyle)
                .apply {
                    text = label
                    isAllCaps = false
                    setTextColor(ContextCompat.getColor(activity, textColor))
                    backgroundTintList = null
                    this.background = background
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setOnClickListener { onClick() }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48)
                    ).apply { topMargin = dp(8) }
                }
        }

        private fun divider(): View = View(activity).apply {
            setBackgroundColor(ContextCompat.getColor(activity, R.color.cw_color_outline_variant))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1).coerceAtLeast(1)
            )
        }

        private fun spacer(height: Int): View = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        }

        /**
         * The screen's content, bounded by the screen itself.
         *
         * A family may be long, and an unbounded column would push the buttons off the
         * bottom; the height is therefore the content's own, up to what the display
         * leaves for a dialog.
         */
        private fun scrollable(content: View): ScrollView {
            val metrics = activity.resources.displayMetrics
            val availableWidth = (metrics.widthPixels * 0.86f).toInt()
            content.measure(
                View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val maxHeight = (metrics.heightPixels * 0.7f).toInt()
            return ScrollView(activity).apply {
                addView(
                    content,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    content.measuredHeight.coerceAtMost(maxHeight)
                )
            }
        }

        private fun dp(value: Int): Int =
            (value * activity.resources.displayMetrics.density).toInt()
    }
}
