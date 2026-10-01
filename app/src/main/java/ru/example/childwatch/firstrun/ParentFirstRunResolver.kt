package ru.example.childwatch.firstrun

import android.content.Context
import android.util.Log
import ru.childwatch.shared.family.FamilyRole
import ru.example.childwatch.network.AuthenticatedMembershipData
import ru.example.childwatch.network.NetworkClient

/** A phone of the child that this family already knows about. */
data class FamilyChildPhone(
    val deviceId: String,
    val displayName: String,
    val isActive: Boolean = true
)

/** The person this phone belongs to, as the family holds them. */
data class FamilySelfPerson(
    val memberId: String,
    val familyId: String,
    val displayName: String,
    val avatarKey: String?
)

/**
 * What the first screen is allowed to say before it asks anything.
 *
 * Nothing here is a guess: the fields come from the server's own answer, so the
 * screen can state a fact ("this phone is already in the family") instead of
 * offering work that has already been done.
 */
data class FamilyFirstRunState(
    val serverReachable: Boolean = false,
    val familyId: String? = null,
    val familyName: String? = null,
    val self: FamilySelfPerson? = null,
    val childPhones: List<FamilyChildPhone> = emptyList(),
    val ownDeviceId: String? = null
) {
    /** True when this phone is attached to a family on the server. */
    val inFamily: Boolean get() = familyId != null && self != null

    /**
     * True when a phone of the child is known, so the family already exists and
     * the person must not be asked to start one.
     */
    val knowsChildPhone: Boolean get() = childPhones.isNotEmpty()

    /** The phone to pick up next, when the step needs the other device. */
    fun suggestedChildPhone(): FamilyChildPhone? =
        childPhones.firstOrNull { it.isActive } ?: childPhones.firstOrNull()
}

/**
 * Asks the server what already exists, so the first screen does not have to
 * assume that an empty local setup means an empty family.
 */
class ParentFirstRunResolver(context: Context) {

    private val appContext = context.applicationContext
    private val networkClient by lazy { NetworkClient(appContext) }

    suspend fun resolve(): FamilyFirstRunState {
        val reachable = runCatching { networkClient.ensureOnboardingAuthentication() }
            .onFailure { Log.w(TAG, "Onboarding registration failed", it) }
            .getOrDefault(false)
        if (!reachable) return FamilyFirstRunState(serverReachable = false)

        val response = runCatching { networkClient.getAuthenticatedIdentity() }
            .onFailure { Log.w(TAG, "Identity lookup failed", it) }
            .getOrNull()
        val body = response?.body()
        if (response == null || !response.isSuccessful || body == null || !body.success) {
            Log.w(TAG, "Identity lookup was not answered by the server")
            return FamilyFirstRunState(serverReachable = false)
        }

        val membership = body.memberships.firstOrNull { it.member.isActive }
            ?: body.memberships.firstOrNull()
        if (membership == null) {
            return FamilyFirstRunState(
                serverReachable = true,
                ownDeviceId = body.device.deviceId.takeIf(String::isNotBlank)
            )
        }

        val familyId = membership.familyId.trim()
        return FamilyFirstRunState(
            serverReachable = true,
            familyId = familyId.takeIf(String::isNotBlank),
            familyName = membership.family.name.trim().takeIf(String::isNotBlank),
            self = membership.toSelfPerson(),
            childPhones = loadChildPhones(familyId),
            ownDeviceId = body.device.deviceId.takeIf(String::isNotBlank)
        )
    }

    /**
     * The phones of the child in this person's family.
     *
     * A failure here is not a failure of the whole answer: the family is still
     * known, and the screen only loses the sentence about which phone to pick
     * up next.
     */
    private suspend fun loadChildPhones(familyId: String): List<FamilyChildPhone> {
        if (familyId.isBlank()) return emptyList()
        val members = runCatching { networkClient.getFamilyMembers(familyId) }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.members
            .orEmpty()
        val devices = runCatching { networkClient.getFamilyDevices(familyId) }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.devices
            .orEmpty()
        if (devices.isEmpty()) return emptyList()

        val childMemberIds = members
            .filter { it.isActive != 0 && it.role.trim().equals("CHILD", ignoreCase = true) }
            .map { it.id.trim() }
            .toSet()
        val childNameByMemberId = members.associate { it.id.trim() to it.displayName.trim() }

        return devices
            .filter { it.isActive != 0 && it.deviceId.isNotBlank() }
            .filter { device ->
                // Without a member list the role cannot be checked, and a family
                // holds far more grown-ups than children: a device of an adult
                // must never be offered as "the phone of the child".
                childMemberIds.isNotEmpty() && device.memberId.trim() in childMemberIds
            }
            .map { device ->
                FamilyChildPhone(
                    deviceId = device.deviceId.trim(),
                    displayName = childNameByMemberId[device.memberId.trim()]
                        ?.takeIf(String::isNotBlank)
                        ?: device.displayName.trim().takeIf(String::isNotBlank)
                        ?: appContext.getString(
                            ru.example.childwatch.R.string.first_run_child_phone_fallback
                        ),
                    isActive = device.isActive != 0
                )
            }
            .distinctBy { it.deviceId }
            .sortedBy { it.displayName.lowercase() }
    }

    private fun AuthenticatedMembershipData.toSelfPerson(): FamilySelfPerson? {
        val memberId = memberId.trim()
        val id = familyId.trim()
        if (memberId.isBlank() || id.isBlank()) return null
        val name = member.displayName.trim().takeIf(String::isNotBlank)
            ?: appContext.getString(ru.example.childwatch.R.string.parent_setup_default_name)
        val role = member.role.trim().uppercase()
        // Only an adult belongs in ParentMonitor; a child record here would mean
        // the phone is attached to the wrong application.
        if (role.isNotEmpty() && role !in ADULT_ROLES) {
            Log.w(TAG, "Membership role $role is not an adult role; ignoring it")
            return null
        }
        return FamilySelfPerson(
            memberId = memberId,
            familyId = id,
            displayName = name,
            avatarKey = member.avatarKey?.trim()?.takeIf(String::isNotBlank)
        )
    }

    private companion object {
        const val TAG = "ParentFirstRun"
        val ADULT_ROLES = setOf(
            FamilyRole.PARENT.name,
            FamilyRole.GUARDIAN.name,
            "RELATIVE"
        )
    }
}
