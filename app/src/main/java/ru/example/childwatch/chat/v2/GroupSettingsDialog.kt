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
import ru.example.childwatch.R
import ru.example.childwatch.profile.FamilyAvatarRenderer

/**
 * Shared settings of a conversation: its name and its picture.
 *
 * The settings belong to the conversation and are changed once for everybody, so they
 * are read from the server rather than from the local cache, which also answers whether
 * this device may change them at all. Everyone else sees the same two entries and is
 * told who does the changing.
 *
 * A group reaches these settings through the group screen, which also holds the
 * membership; for the family chat they are the family's own name and picture, and a
 * rename here renames the family itself.
 */
object GroupSettingsDialog {

    /** Which of the two settings to open, so the group screen can go straight to one. */
    enum class Prompt { MENU, TITLE, AVATAR }

    /**
     * Opens the settings of [conversationId].
     *
     * @param onChanged called after the server accepted a change, so the caller can
     *        refresh the list and the chat header
     */
    fun show(
        activity: Activity,
        scope: CoroutineScope,
        repository: ChatV2Repository,
        conversationId: String,
        contextTitle: String,
        startWith: Prompt = Prompt.MENU,
        onChanged: () -> Unit
    ) {
        scope.launch {
            val settings = repository.loadGroupSettings(conversationId)
            if (settings == null) {
                android.widget.Toast.makeText(
                    activity,
                    R.string.group_settings_unavailable,
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            activity.runOnUiThread {
                when (startWith) {
                    Prompt.TITLE -> if (settings.canManage) {
                        promptTitle(
                            activity,
                            scope,
                            repository,
                            conversationId,
                            settings.title?.takeIf { it.isNotBlank() } ?: contextTitle,
                            onChanged
                        )
                    }
                    Prompt.AVATAR -> if (settings.canManage) {
                        promptAvatar(activity, scope, repository, conversationId, settings, onChanged)
                    }
                    Prompt.MENU -> showLoaded(
                        activity,
                        scope,
                        repository,
                        conversationId,
                        contextTitle,
                        settings,
                        onChanged
                    )
                }
            }
        }
    }

    private fun showLoaded(
        activity: Activity,
        scope: CoroutineScope,
        repository: ChatV2Repository,
        conversationId: String,
        contextTitle: String,
        settings: ChatV2GroupSettingsResponse,
        onChanged: () -> Unit
    ) {
        val title = settings.title?.takeIf { it.isNotBlank() } ?: contextTitle
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
                    0 -> promptTitle(activity, scope, repository, conversationId, title, onChanged)
                    1 -> promptAvatar(
                        activity,
                        scope,
                        repository,
                        conversationId,
                        settings,
                        onChanged
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptTitle(
        activity: Activity,
        scope: CoroutineScope,
        repository: ChatV2Repository,
        conversationId: String,
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
                    val updated = repository.renameGroup(conversationId, name)
                    report(activity, updated != null)
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
        conversationId: String,
        settings: ChatV2GroupSettingsResponse,
        onChanged: () -> Unit
    ) {
        val currentAvatarKey = settings.avatarKey
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
                    val updated = repository.updateGroupAvatar(conversationId, selected)
                    report(activity, updated != null)
                    if (updated != null) onChanged()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** One answer for both settings: saved, or refused without a reason to quote. */
    private fun report(activity: Activity, saved: Boolean) {
        android.widget.Toast.makeText(
            activity,
            if (saved) R.string.group_settings_saved else R.string.group_settings_failed,
            if (saved) android.widget.Toast.LENGTH_SHORT else android.widget.Toast.LENGTH_LONG
        ).show()
    }
}
