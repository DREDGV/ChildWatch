package ru.example.childwatch.chat.v2

import android.app.Activity
import android.content.res.ColorStateList
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.childwatch.shared.chat.ConversationMember
import ru.example.childwatch.R

/**
 * Creates a group: a name and the family members who are in it.
 *
 * Only family members can be asked in, so the candidates are the people the family
 * conversation already carries and the local user is left out — the server puts the
 * creator in the group itself and makes them its administrator, and naming oneself
 * would only be refused.
 *
 * Every refusal is answered inside this screen: the dialog stays open, the missing
 * thing is named under the field it belongs to, and the form keeps what was typed and
 * ticked. A dialog that closed on a refusal left a person with nothing to correct and
 * no way to tell whether the group had been made at all.
 */
object GroupCreationDialog {

    /** The server refuses a longer name, so the form counts up to this and no further. */
    private const val MAX_TITLE_LENGTH = GroupDialogs.MAX_TITLE_LENGTH

    fun show(
        activity: Activity,
        scope: CoroutineScope,
        repository: ChatV2Repository,
        candidates: List<ConversationMember>,
        onCreated: (String) -> Unit
    ) {
        val selectable = candidates
            .filterNot(ConversationMember::isLocalUser)
            .distinctBy(ConversationMember::memberId)
            .sortedBy(ConversationMember::displayName)
        if (selectable.isEmpty()) {
            Toast.makeText(activity, R.string.chat_v2_no_members, Toast.LENGTH_SHORT).show()
            return
        }

        val density = activity.resources.displayMetrics.density
        val padding = (20 * density).toInt()

        // The limit is shown as a counter rather than enforced by a filter: cutting a
        // pasted name short would change what was asked for without saying so.
        val nameField = TextInputLayout(activity).apply {
            hint = activity.getString(R.string.group_name_hint)
            setCounterEnabled(true)
            counterMaxLength = MAX_TITLE_LENGTH
        }
        val nameInput = TextInputEditText(nameField.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        nameField.addView(nameInput)
        val membersLabel = TextView(activity).apply {
            text = activity.getString(R.string.group_create_members_label)
            setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
        }
        val primary = ContextCompat.getColor(activity, R.color.cw_color_primary)
        val checkBoxes = selectable.map { member ->
            CheckBox(activity).apply {
                text = member.displayName
                buttonTintList = ColorStateList.valueOf(primary)
            }
        }
        val membersColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            checkBoxes.forEach(::addView)
        }
        val scroll = ScrollView(activity).apply {
            addView(
                membersColumn,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // The list is given its own height rather than a share of what is left: a
            // long family scrolls inside the dialog instead of pushing the buttons off
            // the screen, and a short one leaves no gap where it was stretched.
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                listHeight(activity, membersColumn)
            )
        }
        val membersError = errorText(activity)
        val requestError = errorText(activity)
        val progress = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, (8 * density).toInt(), 0, 0)
            addView(
                CircularProgressIndicator(activity).apply {
                    isIndeterminate = true
                    trackThickness = (3 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(
                        (18 * density).toInt(),
                        (18 * density).toInt()
                    )
                }
            )
            addView(
                TextView(activity).apply {
                    text = activity.getString(R.string.group_create_in_progress)
                    setPadding((10 * density).toInt(), 0, 0, 0)
                }
            )
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(nameField)
            addView(membersLabel)
            addView(scroll)
            addView(membersError)
            addView(requestError)
            addView(progress)
        }

        // A correction clears the sentence about it, so what is on screen always
        // describes the form as it stands now.
        nameInput.doAfterTextChanged { nameField.error = null }
        checkBoxes.forEach { box ->
            box.setOnCheckedChangeListener { _, _ -> membersError.visibility = View.GONE }
        }

        fun tickedMemberIds(): List<String> = selectable
            .filterIndexed { index, _ -> checkBoxes[index].isChecked }
            .map(ConversationMember::memberId)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.group_new_title)
            .setView(content)
            // Answered below instead of by the builder, because a refusal has to leave
            // the screen open.
            .setPositiveButton(R.string.group_create_confirm, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun setBusy(busy: Boolean) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !busy
            progress.visibility = if (busy) View.VISIBLE else View.GONE
        }

        fun reportRefusal(error: Throwable) {
            // The server names what it refused; a form that can only be refused for a
            // handful of reasons sends the sentence to the place that reason belongs.
            val message = ChatV2ErrorText.failure(activity, error)
            when (ChatV2ErrorText.codeOf(error)) {
                "GROUP_TITLE_REQUIRED", "GROUP_TITLE_TOO_LONG" -> nameField.error = message
                "GROUP_MEMBERS_REQUIRED", "GROUP_TARGET_NOT_AVAILABLE",
                "GROUP_MEMBER_NOT_FOUND" -> {
                    membersError.text = message
                    membersError.visibility = View.VISIBLE
                }
                else -> {
                    requestError.text = message
                    requestError.visibility = View.VISIBLE
                }
            }
        }

        fun submit() {
            nameField.error = null
            membersError.visibility = View.GONE
            requestError.visibility = View.GONE
            val title = nameInput.text?.toString().orEmpty().trim()
            val memberIds = tickedMemberIds()
            // Both are checked before the request, so the answer is immediate and
            // the server is not asked to refuse a form that is plainly incomplete.
            when {
                title.isEmpty() ->
                    nameField.error = activity.getString(R.string.group_create_no_name)
                title.length > MAX_TITLE_LENGTH ->
                    nameField.error = activity.getString(R.string.group_error_title_too_long)
                memberIds.isEmpty() -> {
                    membersError.text = activity.getString(R.string.group_create_members_empty)
                    membersError.visibility = View.VISIBLE
                }
                else -> {
                    setBusy(true)
                    scope.launch {
                        try {
                            val created = repository.createGroup(title, memberIds)
                            activity.runOnUiThread {
                                setBusy(false)
                                dialog.dismiss()
                                onCreated(created.conversationId)
                            }
                        } catch (error: Exception) {
                            // The group was not made: the form stays as it was so the
                            // same attempt can be repeated once the cause is gone.
                            activity.runOnUiThread {
                                setBusy(false)
                                reportRefusal(error)
                            }
                        }
                    }
                }
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { submit() }
        }
        dialog.show()
    }

    /** A sentence in the place a mistake belongs, in the colour mistakes are shown in. */
    private fun errorText(activity: Activity): TextView = TextView(activity).apply {
        TextViewCompat.setTextAppearance(this, R.style.TextAppearance_ChildWatch_TextInput_Error)
        visibility = View.GONE
    }

    /**
     * How tall the list of people may be.
     *
     * Its own height, up to the share of the display a dialog can sensibly take: the
     * people on offer are what the form is about, so the list is measured rather than
     * left to whatever the dialog's remaining space happens to be.
     */
    private fun listHeight(activity: Activity, column: View): Int {
        val metrics = activity.resources.displayMetrics
        val availableWidth = (metrics.widthPixels * 0.8f).toInt()
        column.measure(
            View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val maxHeight = (metrics.heightPixels * 0.45f).toInt()
        return column.measuredHeight.coerceIn(1, maxHeight)
    }
}
