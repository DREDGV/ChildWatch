package ru.childwatch.shared.family

/**
 * A person is the user-facing identity. A device is only one technical way to
 * reach that person. Keeping this distinction in the shared module prevents
 * screens from inventing their own device-id based "profiles".
 */
data class FamilyPersonProfile(
    val member: FamilyMember,
    val devices: List<FamilyDevice>,
    val allowedFeatures: Set<FamilyFeature> = emptySet()
) {
    val activeDevices: List<FamilyDevice>
        get() = devices.filter(FamilyDevice::isActive)

    fun supports(feature: FamilyFeature): Boolean {
        return allowedFeatures.isEmpty() || feature in allowedFeatures
    }

    /**
     * Resolve a concrete endpoint only at the boundary where a feature needs
     * to contact a phone. A previously selected active phone wins; otherwise
     * the most recently seen active phone is used deterministically.
     */
    fun primaryDevice(preferredDeviceId: String? = null): FamilyDevice? {
        val normalizedPreferred = preferredDeviceId.normalizedOrNull()
        val candidates = activeDevices
        if (normalizedPreferred != null) {
            candidates.firstOrNull { it.deviceId == normalizedPreferred }?.let { return it }
        }
        return candidates.sortedWith(
            compareByDescending<FamilyDevice> { it.lastSeenAt ?: Long.MIN_VALUE }
                .thenBy { it.displayName.lowercase() }
                .thenBy { it.deviceId }
        ).firstOrNull()
    }

    fun presence(
        now: Long = System.currentTimeMillis(),
        onlineWindowMs: Long = DEFAULT_ONLINE_WINDOW_MS,
        recentWindowMs: Long = DEFAULT_RECENT_WINDOW_MS
    ): FamilyPresenceState {
        val lastSeenAt = activeDevices.mapNotNull(FamilyDevice::lastSeenAt).maxOrNull()
            ?: return FamilyPresenceState.UNKNOWN
        val age = (now - lastSeenAt).coerceAtLeast(0L)
        return when {
            age <= onlineWindowMs -> FamilyPresenceState.ONLINE
            age <= recentWindowMs -> FamilyPresenceState.RECENTLY_ACTIVE
            else -> FamilyPresenceState.OFFLINE
        }
    }

    companion object {
        const val DEFAULT_ONLINE_WINDOW_MS = 5 * 60 * 1000L
        const val DEFAULT_RECENT_WINDOW_MS = 24 * 60 * 60 * 1000L
    }
}

enum class FamilyPresenceState {
    ONLINE,
    RECENTLY_ACTIVE,
    OFFLINE,
    UNKNOWN
}

data class FamilyDirectorySnapshot(
    val family: Family,
    val selfMemberId: String?,
    val people: List<FamilyPersonProfile>,
    val refreshedAt: Long = System.currentTimeMillis()
) {
    fun person(memberId: String?): FamilyPersonProfile? {
        val normalized = memberId.normalizedOrNull() ?: return null
        return people.firstOrNull { it.member.id == normalized }
    }

    fun personByDeviceId(deviceId: String?): FamilyPersonProfile? {
        val normalized = deviceId.normalizedOrNull() ?: return null
        return people.firstOrNull { person ->
            person.activeDevices.any { it.deviceId == normalized }
        }
    }

    fun targetPeople(feature: FamilyFeature? = null): List<FamilyPersonProfile> {
        return people
            .asSequence()
            .filter { it.member.id != selfMemberId }
            .filter { it.primaryDevice() != null }
            .filter { feature == null || it.supports(feature) }
            .sortedWith(
                compareBy<FamilyPersonProfile> { roleOrder(it.member.role) }
                    .thenBy { it.member.displayName.lowercase() }
                    .thenBy { it.member.id }
            )
            .toList()
    }

    fun resolveTargetDevice(
        memberId: String?,
        preferredDeviceId: String? = null,
        feature: FamilyFeature? = null
    ): FamilyDevice? {
        val person = person(memberId) ?: personByDeviceId(preferredDeviceId) ?: return null
        if (person.member.id == selfMemberId) return null
        if (feature != null && !person.supports(feature)) return null
        return person.primaryDevice(preferredDeviceId)
    }

    private fun roleOrder(role: FamilyRole): Int = when (role) {
        FamilyRole.CHILD -> 0
        FamilyRole.PARENT -> 1
        FamilyRole.GUARDIAN -> 2
    }
}

object FamilyDirectoryAssembler {
    fun assemble(
        family: Family,
        members: List<FamilyMember>,
        devices: List<FamilyDevice>,
        selfMemberId: String? = null,
        permissionsByTargetMember: Map<String, Set<FamilyFeature>> = emptyMap(),
        refreshedAt: Long = System.currentTimeMillis()
    ): FamilyDirectorySnapshot {
        val activeMembers = members
            .asSequence()
            .filter { it.isActive && it.familyId == family.id }
            .distinctBy(FamilyMember::id)
            .toList()

        val activeMemberIds = activeMembers.mapTo(mutableSetOf(), FamilyMember::id)
        val validDevices = devices
            .asSequence()
            .filter { it.isActive }
            .filter { it.familyId == family.id && it.memberId in activeMemberIds }
            .distinctBy(FamilyDevice::deviceId)
            .toList()

        val people = activeMembers.map { member ->
            FamilyPersonProfile(
                member = member,
                devices = validDevices.filter { it.memberId == member.id },
                allowedFeatures = permissionsByTargetMember[member.id].orEmpty()
            )
        }

        return FamilyDirectorySnapshot(
            family = family,
            selfMemberId = selfMemberId.normalizedOrNull(),
            people = people,
            refreshedAt = refreshedAt
        )
    }

    /** SQLite timestamps from older server tables can be seconds or millis. */
    fun epochMillis(value: Long?): Long? {
        val timestamp = value?.takeIf { it > 0L } ?: return null
        return if (timestamp < 100_000_000_000L) timestamp * 1000L else timestamp
    }
}
