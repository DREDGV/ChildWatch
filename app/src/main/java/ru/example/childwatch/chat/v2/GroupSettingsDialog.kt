package ru.example.childwatch.chat.v2

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
 *
 * Both editors answer in place: a name that is empty, too long or unchanged, and a
 * refusal from the server, are shown under the field they are about while the editor
 * stays open. Closing on such an answer used to leave a person with nothing to correct.
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
                GroupDialogs.toast(activity, activity.getString(R.string.group_settings_unavailable))
                return@launch
            }
            activity.runOnUiThread {
                when (startWith) {
                    // Asked to edit without the right to: said plainly rather than
                    // answered with a screen that never appeared.
                    Prompt.TITLE -> if (settings.canManage) {
                        promptTitle(
                            activity,
                            scope,
                            repository,
                            conversationId,
                            settings.title?.takeIf { it.isNotBlank() } ?: contextTitle,
                            onChanged
                        )
                    } else {
                        GroupDialogs.toast(activity, activity.getString(R.string.group_read_only))
                    }
                    Prompt.AVATAR -> if (settings.canManage) {
                        promptAvatar(activity, scope, repository, conversationId, settings, onChanged)
                    } else {
                        GroupDialogs.toast(activity, activity.getString(R.string.group_read_only))
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
        val density = activity.resources.displayMetrics.density
        // A counter rather than a filter: silently cutting a pasted name short would
        // change what was asked for without saying so.
        val field = TextInputLayout(activity).apply {
            hint = activity.getString(R.string.group_name_hint)
            setCounterEnabled(true)
            counterMaxLength = GroupDialogs.MAX_TITLE_LENGTH
        }
        val input = TextInputEditText(field.context).apply {
            setText(current)
            setSelection(current.length)
        }
        field.addView(input)
        val container = LinearLayout(activity).apply {
            val pad = (20 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.group_action_rename)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun setBusy(busy: Boolean) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !busy
        }

        fun submit() {
            field.error = null
            val name = input.text?.toString().orEmpty().trim()
            when {
                name.isEmpty() ->
                    field.error = activity.getString(R.string.group_create_no_name)
                name.length > GroupDialogs.MAX_TITLE_LENGTH ->
                    field.error = activity.getString(R.string.group_error_title_too_long)
                name == current ->
                    field.error = activity.getString(R.string.group_title_unchanged)
                else -> {
                    setBusy(true)
                    scope.launch {
                        try {
                            repository.renameGroup(conversationId, name)
                            activity.runOnUiThread {
                                setBusy(false)
                                dialog.dismiss()
                                GroupDialogs.toast(
                                    activity,
                                    activity.getString(R.string.group_title_saved)
                                )
                                onChanged()
                            }
                        } catch (error: Exception) {
                            activity.runOnUiThread {
                                setBusy(false)
                                field.error = ChatV2ErrorText.failure(activity, error)
                            }
                        }
                    }
                }
            }
        }

        // A correction clears the sentence about it, so what is on screen describes the
        // field as it stands now.
        input.doAfterTextChanged { field.error = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { submit() }
        }
        dialog.show()
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

        val errorText = TextView(activity).apply {
            TextViewCompat.setTextAppearance(
                this,
                R.style.TextAppearance_ChildWatch_TextInput_Error
            )
            visibility = View.GONE
        }

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
                    errorText.visibility = View.GONE
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
                TextView(activity).apply {
                    text = activity.getString(R.string.group_avatar_hint)
                    setPadding(0, 0, 0, (8 * density).toInt())
                    visibility = View.VISIBLE
                }
            )
            addView(scroll)
            addView(errorText)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.group_action_avatar)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun setBusy(busy: Boolean) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !busy
        }

        fun submit() {
            errorText.visibility = View.GONE
            if (selected == currentAvatarKey) {
                errorText.text = activity.getString(R.string.group_avatar_unchanged)
                errorText.visibility = View.VISIBLE
                return
            }
            setBusy(true)
            scope.launch {
                try {
                    repository.updateGroupAvatar(conversationId, selected)
                    activity.runOnUiThread {
                        setBusy(false)
                        dialog.dismiss()
                        GroupDialogs.toast(
                            activity,
                            activity.getString(R.string.group_avatar_saved)
                        )
                        onChanged()
                    }
                } catch (error: Exception) {
                    activity.runOnUiThread {
                        setBusy(false)
                        errorText.text = ChatV2ErrorText.failure(activity, error)
                        errorText.visibility = View.VISIBLE
                    }
                }
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { submit() }
        }
        dialog.show()
    }
}
