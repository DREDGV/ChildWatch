package ru.example.childwatch.chat.v2

import android.content.Context
import ru.example.childwatch.R

/**
 * Turns the server's reason for a refused group change into a sentence for the user.
 *
 * The codes are the contract: they are stable, while the sentence the server sends
 * beside one is written for a developer. A code this build does not know is answered
 * with the general sentence rather than shown as it stands, so no raw code ever
 * reaches the screen.
 */
object ChatV2ErrorText {

    fun groupError(context: Context, code: String?): String = when (code) {
        "GROUP_ADMIN_REQUIRED" -> context.getString(R.string.group_error_admin_required)
        "GROUP_ADMIN_CANNOT_LEAVE" -> context.getString(R.string.group_error_admin_cannot_leave)
        "GROUP_ADMIN_ALREADY" -> context.getString(R.string.group_error_admin_already)
        "GROUP_ADMIN_CANNOT_REMOVE_SELF" ->
            context.getString(R.string.group_error_admin_cannot_remove_self)
        "GROUP_MEMBER_NOT_FOUND" -> context.getString(R.string.group_error_member_not_found)
        "GROUP_TARGET_NOT_AVAILABLE" -> context.getString(R.string.group_error_target_not_available)
        "GROUP_MEMBERS_REQUIRED" -> context.getString(R.string.group_error_members_required)
        "GROUP_TITLE_REQUIRED" -> context.getString(R.string.group_error_title_required)
        "GROUP_TITLE_TOO_LONG" -> context.getString(R.string.group_error_title_too_long)
        "NOT_A_GROUP_CONVERSATION" -> context.getString(R.string.group_error_not_a_group)
        "GROUP_AVATAR_UNSUPPORTED" -> context.getString(R.string.group_error_avatar_unsupported)
        "CONVERSATION_ACCESS_DENIED" -> context.getString(R.string.group_error_access_denied)
        else -> context.getString(R.string.group_error_generic)
    }

    /**
     * Answers a failed [ChatV2Repository] call.
     *
     * A call that never reached the server carries no code of the server's, so it is
     * reported as the missing connection it is — and as a change that did not happen,
     * because a group is only ever changed by the server and never by this phone alone.
     */
    fun failure(context: Context, error: Throwable): String {
        val code = codeOf(error) ?: return context.getString(R.string.group_error_offline)
        return groupError(context, code)
    }

    /**
     * The server's own code behind a failed call, or null when it never arrived.
     *
     * A form needs the code as well as the sentence: the same refusal is shown under
     * the field it is about, and a call that never reached the server belongs to no
     * field at all.
     */
    fun codeOf(error: Throwable): String? =
        (error as? ChatV2RepositoryException)?.code?.let(::rawCode)

    /**
     * Recovers the server's own code from an exception label.
     *
     * The repository prefixes the operation for its log, as in
     * `ADD_GROUP_MEMBERS_HTTP_403`; the trailing status is dropped here so the
     * remainder can be matched against the codes above.
     */
    private fun rawCode(code: String): String =
        code.substringBefore("_HTTP_").substringBefore("_EMPTY_BODY")
}
