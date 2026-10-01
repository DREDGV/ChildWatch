package ru.example.childwatch.profile

import android.content.Context
import ru.childwatch.shared.onboarding.OnboardingMemberData
import ru.example.childwatch.ParentSetupActivity
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.database.entity.Parent

/** Persist only the confirmed server identity, never the invitation form's cache. */
object ParentInvitationCompletion {
    suspend fun persist(context: Context, member: OnboardingMemberData) {
        val id = requireNotNull(member.id?.takeIf(String::isNotBlank))
        val dao = ChildWatchDatabase.getInstance(context).parentDao()
        val existing = dao.getByAccountId(id) ?: dao.getAll().firstOrNull()
        val parentId = dao.insert(Parent(
            id = existing?.id ?: 0L, accountId = id, name = member.displayName,
            email = existing?.email ?: "parent@childwatch.local",
            phoneNumber = existing?.phoneNumber, avatarUrl = member.avatarKey,
            passwordHash = existing?.passwordHash, isVerified = true,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        ))
        context.getSharedPreferences(ParentSetupActivity.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(ParentSetupActivity.KEY_ONBOARDING_COMPLETED, true)
            .putLong("parent_id", parentId).apply()
        context.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE).edit()
            .putString(ParentParticipantNameResolver.KEY_SELF_DISPLAY_NAME, member.displayName).apply()
    }
}
