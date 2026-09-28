package ru.example.childwatch.chat.v2

import android.app.Activity
import android.content.res.ColorStateList
import android.text.InputFilter
import android.text.InputType
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
 * The ticks and the typed name are read before anything is refused, so a name that is
 * still empty costs one correction rather than the whole form.
 */
object GroupCreationDialog {

    /** The server refuses a longer name, so the form does not let one be typed. */
    private const val MAX_TITLE_LENGTH = 64

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

        val titleInput = EditText(activity).apply {
            hint = activity.getString(R.string.group_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(MAX_TITLE_LENGTH))
        }
        val titleLabel = TextView(activity).apply {
            text = activity.getString(R.string.group_create_name_label)
            setPadding(0, 0, 0, (4 * density).toInt())
        }
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
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(titleLabel)
            addView(titleInput)
            addView(membersLabel)
            // The list takes whatever height is left, so a long family scrolls inside
            // the dialog instead of pushing the buttons off the screen.
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }

        fun tickedMemberIds(): List<String> = selectable
            .filterIndexed { index, _ -> checkBoxes[index].isChecked }
            .map(ConversationMember::memberId)

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.group_new_title)
            .setView(content)
            .setPositiveButton(R.string.group_create_confirm) { _, _ ->
                val title = titleInput.text?.toString().orEmpty().trim()
                val memberIds = tickedMemberIds()
                // Both are checked before the request, so the answer is immediate and
                // the server is not asked to refuse a form that is plainly incomplete.
                when {
                    title.isEmpty() ->
                        toast(activity, activity.getString(R.string.group_create_no_name))
                    memberIds.isEmpty() ->
                        toast(activity, activity.getString(R.string.group_create_members_empty))
                    else -> scope.launch {
                        try {
                            val created = repository.createGroup(title, memberIds)
                            activity.runOnUiThread { onCreated(created.conversationId) }
                        } catch (error: Exception) {
                            activity.runOnUiThread {
                                toast(activity, ChatV2ErrorText.failure(activity, error))
                            }
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(activity: Activity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
