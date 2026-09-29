package ru.example.childwatch.chat.v2

import android.app.Activity
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.example.childwatch.R

/**
 * The loading, the refusal and the confirmation of a group change, in one place.
 *
 * Every group action ends the same way: the server is asked, a refusal is turned into
 * a sentence, the conversation list is refreshed so the device reads the membership the
 * server now holds, and the screen is opened again on what is left. Writing that once
 * keeps the actions from drifting apart in how they report themselves.
 */
internal object GroupDialogs {

    /**
     * The longest group name the server accepts.
     *
     * The limit belongs to the server, and every form that types a name counts up to
     * the same number rather than each carrying its own idea of it.
     */
    const val MAX_TITLE_LENGTH = 64

    fun toast(activity: Activity, message: String) {
        android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show()
    }

    /**
     * Asks the server to change the group and reports the outcome.
     *
     * @param action the request; false means the server answered without raising, which
     *        is a refusal all the same and is not worth a different sentence
     * @param onApplied called only after a change the server accepted
     */
    fun request(
        activity: Activity,
        scope: CoroutineScope,
        onApplied: () -> Unit,
        action: suspend () -> Boolean
    ) {
        scope.launch {
            try {
                val applied = action()
                activity.runOnUiThread {
                    if (applied) onApplied() else toast(activity, activity.getString(R.string.group_error_generic))
                }
            } catch (error: Exception) {
                activity.runOnUiThread {
                    toast(activity, ChatV2ErrorText.failure(activity, error))
                }
            }
        }
    }

    /** A question that names what the answer will do before it is given. */
    fun confirm(
        activity: Activity,
        title: String,
        message: String,
        confirmLabel: String,
        onConfirmed: () -> Unit
    ) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(confirmLabel) { _, _ -> onConfirmed() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** One line of text, asked for and confirmed. */
    fun promptText(
        activity: Activity,
        title: String,
        hint: String,
        current: String,
        onAccepted: (String) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        val input = EditText(activity).apply {
            setText(current)
            setSelection(text.length)
            this.hint = hint
        }
        val container = LinearLayout(activity).apply {
            val pad = (20 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onAccepted(input.text?.toString().orEmpty())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
