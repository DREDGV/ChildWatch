package ru.example.childwatch.profile

import android.content.Context
import ru.childwatch.shared.family.FamilyRole

/** A listening target is one child's actual device, never a fallback selection. */
class ParentListeningTargetPolicy(context: Context) {
    private val repository = ParentFamilyDirectoryRepository(context.applicationContext)

    suspend fun isChildDevice(deviceId: String): Boolean {
        val id = deviceId.trim().takeIf { it.isNotEmpty() } ?: return false
        val directory = repository.load().directory
        val person = directory.people.firstOrNull { profile ->
            profile.activeDevices.any { it.deviceId == id }
        } ?: return false
        return person.member.role == FamilyRole.CHILD && person.member.id != directory.selfMemberId
    }
}
