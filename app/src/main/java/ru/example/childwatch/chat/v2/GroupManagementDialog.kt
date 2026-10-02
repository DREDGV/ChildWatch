package ru.example.childwatch.chat.v2

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.childwatch.shared.chat.ChatV2GroupSettingsResponse
import ru.childwatch.shared.chat.ConversationMember
import ru.childwatch.shared.chat.ConversationMemberRole
import ru.childwatch.shared.chat.toDomain
import ru.example.childwatch.R

/**
 * Everything that can be done to a group conversation.
 *
 * The server is the only authority on a group: it holds the name, the membership and
 * the administrator, and it refuses every change from anybody but the administrator.
 * That is why this screen re-reads the group's settings before each action and refreshes
 * the conversation list after it, rather than trusting what the device happens to have.
 *
 * The composition comes first and the actions after it, in the order of what they do to
 * the group: its name, then its people, then the administrator, and only at the end the
 * two ways of being done with it. What the reader may not do is not shown at all, so a
 * member is never offered a button that would only be refused.
 *
 * Only a group reaches this screen. The family chat keeps its own settings, where a
 * rename renames the family itself; routing it here would quietly take that away.
 */
object GroupManagementDialog {

    /**
     * Opens the screen for one group.
     *
     * @param conversationId the group, whose cached copy the caller has just refreshed
     * @param onRefresh refreshes the conversation list and waits for it, so the next
     *        reading of the group is the server's and not a stale cache
     */
    fun show(
        activity: Activity,
        scope: CoroutineScope,
        repository: ChatV2Repository,
        conversationId: String,
        onRefresh: suspend () -> Unit
    ) {
        scope.launch {
            val settings = repository.loadGroupSettings(conversationId)
            if (activity.isFinishing || activity.isDestroyed) return@launch
            // A local cache miss does not establish that the server closed the group.
            when {
                settings == null -> GroupDialogs.toast(
                    activity,
                    activity.getString(R.string.group_settings_unavailable)
                )
                else -> activity.runOnUiThread {
                    Screen(
                        activity = activity,
                        scope = scope,
                        repository = repository,
                        conversationId = conversationId,
                        onRefresh = onRefresh
                    ).show(settings)
                }
            }
        }
    }

    /**
     * One open screen.
     *
     * The screen is rebuilt from freshly loaded settings after every change, so what is
     * offered always matches the group as the server last described it. That also means
     * the previous copy has to go: one screen at a time, or the reader ends up looking
     * at a description of a group that no longer exists.
     */
    private class Screen(
        private val activity: Activity,
        private val scope: CoroutineScope,
        private val repository: ChatV2Repository,
        private val conversationId: String,
        private val onRefresh: suspend () -> Unit
    ) {
        private var dialog: AlertDialog? = null
        private var closed = false
        private var requestToken = 0

        /**
         * Draws the group as the server last described it.
         *
         * Membership and administrative rights come from the same server snapshot.
         */
        fun show(settings: ChatV2GroupSettingsResponse) {
            if (closed || activity.isFinishing || activity.isDestroyed) return
            val members = settings.members.map {
                it.toDomain(isLocalUser = it.memberId == settings.actorMemberId)
            }.distinctBy(ConversationMember::memberId)
            dialog?.setOnDismissListener(null)
            if (dialog?.isShowing == true) dialog?.dismiss()
            val canManage = settings.canManage
            // The identifier the server recognises this device by; it is what tells the
            // member marked as this reader from everybody else.
            val localMemberId = settings.actorMemberId.orEmpty()
            val title = settings.title?.takeIf { it.isNotBlank() }
                ?: activity.getString(R.string.group_new_title)
            val adminName = members
                .firstOrNull { it.memberId == settings.adminMemberId }
                ?.displayName

            val density = activity.resources.displayMetrics.density
            val pad = (24 * density).toInt()
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 3, pad, pad / 3)
            }

            // Who administers it comes before the membership: it is the answer to "may I
            // change this?", which is the first thing somebody opens these settings for.
            if (!adminName.isNullOrBlank()) {
                content.addView(
                    metaLine(activity.getString(R.string.group_admin_line, adminName))
                )
            }
            content.addView(
                metaLine(
                    activity.resources.getQuantityString(
                        R.plurals.group_member_count,
                        members.size,
                        members.size
                    )
                )
            )
            content.addView(spacer((10 * density).toInt()))
            content.addView(sectionLabel(activity.getString(R.string.group_settings_members)))
            members
                .distinctBy(ConversationMember::memberId)
                .sortedBy(ConversationMember::displayName)
                .forEach { member ->
                    content.addView(
                        memberRow(
                            member = member,
                            isAdmin = member.memberId == settings.adminMemberId,
                            isLocal = localMemberId.isNotEmpty() &&
                                member.memberId == localMemberId
                        )
                    )
                }

            // Neither this device's own member nor the administrator is a candidate for
            // the two actions that name a person: nobody removes themselves, removes the
            // administrator, or hands the group to the person who already holds it.
            val others = members
                .filterNot { it.memberId == localMemberId || it.memberId == settings.adminMemberId }
                .distinctBy(ConversationMember::memberId)
                .sortedBy(ConversationMember::displayName)
            content.addView(spacer((8 * density).toInt()))
            content.addView(divider())
            content.addView(spacer((8 * density).toInt()))

            if (canManage) {
                // In the order of what they do to the group: its name, then its people,
                // then who administers it, and last the way of ending it.
                content.addView(
                    action(activity.getString(R.string.group_action_rename)) {
                        GroupSettingsDialog.show(
                            activity = activity,
                            scope = scope,
                            repository = repository,
                            conversationId = conversationId,
                            contextTitle = title,
                            startWith = GroupSettingsDialog.Prompt.TITLE,
                            onChanged = { refreshAndReopen() }
                        )
                    }
                )
                content.addView(
                    action(activity.getString(R.string.group_action_members_add)) {
                        promptAddMembers()
                    }
                )
                content.addView(
                    action(activity.getString(R.string.group_action_member_remove)) {
                        promptRemoveMember(title, others)
                    }
                )
                content.addView(
                    action(activity.getString(R.string.group_action_admin_transfer)) {
                        promptTransferAdmin(title, others)
                    }
                )
                content.addView(spacer((8 * density).toInt()))
                content.addView(divider())
                content.addView(
                    action(
                        label = activity.getString(R.string.group_action_close),
                        destructive = true
                    ) { promptClose(title) }
                )
            } else {
                content.addView(note(activity.getString(R.string.group_read_only)))
                content.addView(
                    action(
                        label = activity.getString(R.string.group_action_leave),
                        destructive = true
                    ) { promptLeave(title) }
                )
            }

            dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(scrollable(content))
                .setNegativeButton(R.string.group_settings_close, null)
                .create()
                .also {
                    it.setOnDismissListener { closed = true; requestToken++ }
                    it.show()
                }
        }

        /**
         * A list of people to choose from, named by the action that will follow.
         *
         * Removing and handing over ask the same question — which person — so they share
         * this and differ only in what happens to the answer.
         */
        private fun pickMember(
            titleRes: Int,
            labels: List<String>,
            emptyMessage: String,
            onPicked: (Int) -> Unit
        ) {
            if (labels.isEmpty()) {
                GroupDialogs.toast(activity, emptyMessage)
                return
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(titleRes)
                .setItems(labels.toTypedArray()) { _, index -> onPicked(index) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun promptAddMembers() {
            val token = ++requestToken
            scope.launch {
                val settings = repository.loadGroupSettings(conversationId)
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
                    show(settings)
                    GroupDialogs.toast(activity, activity.getString(R.string.group_read_only))
                    return@launch
                }
                val present = settings.members.mapTo(mutableSetOf()) { it.memberId }
                val candidates = refreshed
                    .filterNot { it.memberId == settings.actorMemberId || it.memberId in present }
                    .distinctBy(ConversationMember::memberId)
                    .sortedBy(ConversationMember::displayName)
                showMemberPicker(candidates)
            }
        }

        private fun showMemberPicker(candidates: List<ConversationMember>) {
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
                    onApplied = {
                        // Named, so the answer says who joined and not merely that
                        // something happened.
                        GroupDialogs.toast(
                            activity,
                            activity.getString(
                                R.string.group_members_added,
                                selected.joinToString(", ") { it.displayName }
                            )
                        )
                        refreshAndReopen()
                    }
                ) {
                    repository.addGroupMembers(
                        conversationId,
                        selected.map(ConversationMember::memberId)
                    )
                }
            }
        }

        private fun promptRemoveMember(title: String, candidates: List<ConversationMember>) {
            pickMember(
                titleRes = R.string.group_action_member_remove,
                labels = candidates.map(ConversationMember::displayName),
                emptyMessage = activity.getString(R.string.group_members_none_to_remove)
            ) { index ->
                val member = candidates[index]
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
                        onApplied = {
                            GroupDialogs.toast(
                                activity,
                                activity.getString(
                                    R.string.group_member_removed_done,
                                    member.displayName
                                )
                            )
                            refreshAndReopen()
                        }
                    ) {
                        repository.removeGroupMember(conversationId, member.memberId)
                    }
                }
            }
        }

        private fun promptTransferAdmin(title: String, others: List<ConversationMember>) {
            pickMember(
                titleRes = R.string.group_action_admin_transfer,
                labels = others.map(ConversationMember::displayName),
                emptyMessage = activity.getString(R.string.group_members_none_to_transfer)
            ) { index ->
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
                        onApplied = {
                            GroupDialogs.toast(
                                activity,
                                activity.getString(
                                    R.string.group_admin_transferred_done,
                                    member.displayName
                                )
                            )
                            refreshAndReopen()
                        }
                    ) {
                        repository.transferGroupAdmin(conversationId, member.memberId)
                    }
                }
            }
        }

        /**
         * Leaving.
         *
         * Only a member who does not administer the group is offered this: the server
         * refuses an administrator who tries to leave, because a group whose
         * administrator walked away would have a name and a membership nobody could
         * change. Walking away is the administrator's choice to make through the two
         * actions they are given instead, so the rule is shown as the shape of the
         * screen rather than as an error after the fact.
         */
        private fun promptLeave(title: String) {
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
            scope.launch {
                onRefresh()
                activity.runOnUiThread { if (dialog?.isShowing == true) dialog?.dismiss() }
            }
        }

        /** Reads the group again after a change, then draws what is left of it. */
        private fun refreshAndReopen() {
            val token = ++requestToken
            scope.launch {
                onRefresh()
                if (closed || token != requestToken || activity.isFinishing || activity.isDestroyed) return@launch
                val settings = repository.loadGroupSettings(conversationId)
                activity.runOnUiThread {
                    if (closed || token != requestToken || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    when {
                        settings == null -> GroupDialogs.toast(
                            activity,
                            activity.getString(R.string.group_settings_unavailable)
                        )
                        else -> show(settings)
                    }
                }
            }
        }

        // The pieces the screen is made of. They are built rather than laid out in XML
        // because the membership is a list of unknown length, and each piece is one of
        // the same material components the rest of the application already uses.

        private fun metaLine(text: String): TextView = TextView(activity).apply {
            this.text = text
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(ContextCompat.getColor(activity, R.color.cw_color_on_surface_variant))
        }

        private fun sectionLabel(text: String): TextView = TextView(activity).apply {
            this.text = text
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(ContextCompat.getColor(activity, R.color.cw_color_on_surface_variant))
            setPadding(0, (4 * activity.resources.displayMetrics.density).toInt(), 0, 0)
        }

        private fun note(text: String): TextView = TextView(activity).apply {
            this.text = text
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Material3_BodySmall)
            setTextColor(ContextCompat.getColor(activity, R.color.cw_color_on_surface_variant))
            setPadding(0, 0, 0, (8 * activity.resources.displayMetrics.density).toInt())
        }

        /**
         * One person: their name, and what they are in this group.
         *
         * The role and the marks are what make the list more than a list of names — they
         * are how a reader finds the administrator and themselves.
         */
        private fun memberRow(
            member: ConversationMember,
            isAdmin: Boolean,
            isLocal: Boolean
        ): View {
            val density = activity.resources.displayMetrics.density
            val marks = mutableListOf(roleLabel(member.role))
            if (isAdmin) marks += activity.getString(R.string.group_member_admin_mark)
            if (isLocal) marks += activity.getString(R.string.group_member_you_mark)
            val name = TextView(activity).apply {
                text = member.displayName
                TextViewCompat.setTextAppearance(
                    this,
                    R.style.TextAppearance_Material3_BodyLarge
                )
                setTextColor(ContextCompat.getColor(activity, R.color.cw_color_on_surface))
            }
            val details = TextView(activity).apply {
                text = marks.joinToString(" · ")
                TextViewCompat.setTextAppearance(
                    this,
                    R.style.TextAppearance_Material3_BodySmall
                )
                setTextColor(
                    ContextCompat.getColor(activity, R.color.cw_color_on_surface_variant)
                )
            }
            return LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                addView(name)
                addView(details)
            }
        }

        private fun action(
            label: String,
            destructive: Boolean = false,
            onClick: () -> Unit
        ): MaterialButton = (
            LayoutInflater.from(activity).inflate(R.layout.item_group_action, null, false)
                as MaterialButton
            ).apply {
            text = label
            // Inflated without a parent, so the width the screen gives these rows is
            // stated here instead of being taken from the layout file.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // Destructive actions are coloured as such, so the two ways of leaving a
            // group are not mistaken for the ordinary ones above them.
            setTextColor(
                ContextCompat.getColor(
                    activity,
                    if (destructive) R.color.cw_color_error else R.color.cw_color_primary
                )
            )
            setOnClickListener { onClick() }
        }

        private fun divider(): View = View(activity).apply {
            setBackgroundColor(
                ContextCompat.getColor(activity, R.color.cw_color_outline_variant)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            )
        }

        private fun spacer(height: Int): View = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height
            )
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

        private fun roleLabel(role: ConversationMemberRole): String = when (role) {
            ConversationMemberRole.PARENT -> activity.getString(R.string.family_role_parent)
            ConversationMemberRole.CHILD -> activity.getString(R.string.family_role_child)
            ConversationMemberRole.GUARDIAN -> activity.getString(R.string.family_role_relative)
        }
    }
}
