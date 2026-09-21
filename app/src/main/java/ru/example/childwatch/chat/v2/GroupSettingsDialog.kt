package ru.example.childwatch.chat.v2

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.childwatch.shared.chat.ChatV2GroupSettingsResponse
import ru.childwatch.shared.chat.Conversation
import ru.example.childwatch.R
import ru.example.childwatch.profile.FamilyAvatarRenderer

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
        val labels = mutableListOf<String>()
        if (settings.canManage) {
            labels += activity.getString(R.string.group_action_rename)
            labels += activity.getString(R.string.group_action_avatar)
        } else {
            labels += activity.getString(R.string.group_read_only)
        }

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (!settings.canManage) return@setItems
                when (which) {
                    0 -> promptTitle(activity, scope, repository, conversation, title, onChanged)
                    1 -> promptAvatar(activity, scope, repository, conversation, settings.avatarKey, onChanged)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
                    val updated = repository.renameGroup(conversation.conversationId, name)
                    activity.runOnUiThread {
                        android.widget.Toast.makeText(
                            activity,
                            if (updated != null) {
                                R.string.group_settings_saved
                            } else {
                                R.string.group_settings_failed
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

        // Built from the plain values so each entry shows its own artwork from the
        // shared sheet; the preset list carries placeholder images instead.
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
                    val updated = repository.updateGroupAvatar(conversation.conversationId, selected)
                    activity.runOnUiThread {
                        android.widget.Toast.makeText(
                            activity,
                            if (updated != null) {
                                R.string.group_settings_saved
                            } else {
                                R.string.group_settings_failed
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
}
