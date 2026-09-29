package ru.example.parentwatch.chat.v2

import android.app.Activity
import android.widget.Toast
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.example.parentwatch.R

/**
 * The asking, the refusing and the confirmation of a group change, in one place.
 *
 * Every group action ends the same way: the server is asked, a refusal is turned into
 * a sentence, and the screen is drawn again from what the server now holds. Writing
 * that once keeps the actions from drifting apart in how they report themselves — and
 * it keeps the rule that a refusal is always said in words, never swallowed.
 */
internal object GroupDialogs {

    fun toast(activity: Activity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    /**
     * Asks the server to change the group and reports the outcome.
     *
     * [onRefused] is given the server's own reason so the screen can put the sentence
     * where it belongs; without it the reason is told as a toast, which is all a
     * caller that has nowhere better to put it can do.
     */
    fun request(
        activity: Activity,
        scope: CoroutineScope,
        onApplied: () -> Unit,
        onRefused: (Int) -> Unit = { toast(activity, activity.getString(it)) },
        action: suspend () -> Boolean
    ) {
        scope.launch {
            val outcome = runCatching { action() }
            val applied = outcome.getOrNull() == true
            activity.runOnUiThread {
                if (applied) {
                    onApplied()
                } else {
                    // A call the server never answered is not a change either, and the
                    // sentence for it already says so.
                    onRefused(GroupErrorMessages.forFailure(outcome.exceptionOrNull()))
                }
            }
        }
    }

    /** A question that names what the answer will do before it is given. */
    fun confirm(
        activity: Activity,
        @StringRes title: Int,
        message: String,
        @StringRes confirmLabel: Int,
        onConfirmed: () -> Unit
    ) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(confirmLabel) { _, _ -> onConfirmed() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
