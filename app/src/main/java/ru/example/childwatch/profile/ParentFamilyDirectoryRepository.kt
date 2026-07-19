package ru.example.childwatch.profile

import android.content.Context
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ru.childwatch.shared.family.Family
import ru.childwatch.shared.family.FamilyDevice
import ru.childwatch.shared.family.FamilyDirectoryAssembler
import ru.childwatch.shared.family.FamilyDirectorySnapshot
import ru.childwatch.shared.family.FamilyMember
import ru.childwatch.shared.family.FamilyRole
import ru.childwatch.shared.family.StableContextIds
import ru.example.childwatch.contacts.ContactRoles
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.database.entity.Child
import ru.example.childwatch.network.FamilyDeviceData
import ru.example.childwatch.network.FamilyMemberData
import ru.example.childwatch.network.FamilySummaryData
import ru.example.childwatch.network.NetworkClient

enum class ParentFamilyDirectorySource {
    SERVER,
    LOCAL_FALLBACK
}

data class ParentFamilyDirectoryResult(
    val directory: FamilyDirectorySnapshot,
    val source: ParentFamilyDirectorySource,
    val warning: String? = null
)

/**
 * The single read boundary for family people in ParentMonitor.
 *
 * Server family/member/device records are preferred. The existing Room child
 * table remains a compatibility cache while installed clients migrate. UI
 * code receives people and only resolves a device when launching a feature.
 */
class ParentFamilyDirectoryRepository(context: Context) {

    private val appContext = context.applicationContext
    private val database by lazy { ChildWatchDatabase.getInstance(appContext) }
    private val networkClient by lazy { NetworkClient(appContext) }
    private val contextResolver by lazy { ParentEffectiveContextResolver(appContext) }
    private val contextProvider by lazy { ParentEffectiveContextProvider.get(appContext) }

    suspend fun load(): ParentFamilyDirectoryResult {
        val localChildren = database.childDao().getAll()
        val localParent = database.parentDao().getAll().firstOrNull()
        val canonical = runCatching {
            loadFromServer(
                localChildren = localChildren,
                localParentName = localParent?.name,
                localParentAvatar = localParent?.avatarUrl
            )
        }
            .onFailure { Log.w(TAG, "Canonical family directory is unavailable", it) }
            .getOrNull()

        if (canonical != null) {
            rememberCanonicalIdentity(canonical.directory)
            return canonical
        }

        return ParentFamilyDirectoryResult(
            directory = buildLocalFallback(
                localChildren = localChildren,
                localParentName = localParent?.name,
                localParentAvatar = localParent?.avatarUrl
            ),
            source = ParentFamilyDirectorySource.LOCAL_FALLBACK,
            warning = "Семейный каталог временно собран из данных этого телефона"
        )
    }

    /**
     * Persist the human profile represented by [deviceId] in the canonical
     * family directory. Device-local content URIs deliberately remain local;
     * only portable preset avatar keys can be shared by another phone.
     */
    suspend fun updateProfileForDevice(
        deviceId: String,
        displayName: String,
        avatarValue: String?
    ): Boolean {
        val normalizedDeviceId = deviceId.trim()
        val normalizedName = displayName.trim()
        if (normalizedDeviceId.isBlank() || normalizedName.length < 2) return false

        val directoryResult = runCatching { load() }.getOrNull() ?: return false
        if (directoryResult.source != ParentFamilyDirectorySource.SERVER) return false
        val person = directoryResult.directory.personByDeviceId(normalizedDeviceId) ?: return false
        val response = networkClient.updateFamilyMemberProfile(
            familyId = person.member.familyId,
            memberId = person.member.id,
            displayName = normalizedName,
            avatarKey = avatarValue.toPortableAvatarKey()
        )
        return response.isSuccessful && response.body()?.success == true
    }

    suspend fun updateOwnProfile(displayName: String, avatarValue: String?): Boolean {
        val normalizedName = displayName.trim()
        if (normalizedName.length < 2) return false
        val identity = runCatching { networkClient.getAuthenticatedIdentity() }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?: return false
        val preferredFamilyId = contextResolver.resolveFamilyId().orEmpty().trim()
        val membership = identity.memberships.firstOrNull { it.familyId == preferredFamilyId }
            ?: identity.memberships.firstOrNull()
            ?: return false
        val response = networkClient.updateFamilyMemberProfile(
            familyId = membership.familyId,
            memberId = membership.memberId,
            displayName = normalizedName,
            avatarKey = avatarValue.toPortableAvatarKey()
        )
        return response.isSuccessful && response.body()?.success == true
    }

    private fun String?.toPortableAvatarKey(): String? = this
        ?.trim()
        ?.takeIf { value ->
            value in setOf(
                "preset:sky",
                "preset:mint",
                "preset:sun",
                "preset:coral",
                "preset:lilac",
                "preset:ocean"
            )
        }

    private suspend fun loadFromServer(
        localChildren: List<Child>,
        localParentName: String?,
        localParentAvatar: String?
    ): ParentFamilyDirectoryResult? = coroutineScope {
        val identityDeferred = async { runCatching { networkClient.getAuthenticatedIdentity() }.getOrNull() }
        val familiesResponse = networkClient.getFamilies()
        if (!familiesResponse.isSuccessful) return@coroutineScope null

        val families = familiesResponse.body()?.families
            .orEmpty()
            .filter { it.isActive != 0 && it.id.isNotBlank() }
        if (families.isEmpty()) return@coroutineScope null

        val currentFamilyId = contextResolver.resolveFamilyId().orEmpty().trim()
        val selfDeviceId = contextResolver.resolveOwnParentId().trim()
        val selectedDeviceId = contextResolver.resolveTargetDeviceId().trim()
        val authenticatedMemberships = identityDeferred.await()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.memberships
            .orEmpty()
        val authenticatedByFamily = authenticatedMemberships.associateBy { it.familyId }

        val candidates = families.map { family ->
            async {
                val members = async { networkClient.getFamilyMembers(family.id) }
                val devices = async { networkClient.getFamilyDevices(family.id) }
                val memberResponse = members.await()
                val deviceResponse = devices.await()
                if (!memberResponse.isSuccessful || !deviceResponse.isSuccessful) return@async null
                ServerDirectoryCandidate(
                    family = family,
                    members = memberResponse.body()?.members.orEmpty(),
                    devices = deviceResponse.body()?.devices.orEmpty()
                )
            }
        }.mapNotNull { it.await() }

        val selected = candidates.maxWithOrNull(
            compareBy<ServerDirectoryCandidate> {
                it.matchScore(currentFamilyId, selfDeviceId, selectedDeviceId)
                    + if (it.family.id in authenticatedByFamily) 50 else 0
            }.thenBy { it.family.updatedAt }
        ) ?: return@coroutineScope null

        val localByDevice = localChildren.associateBy { it.deviceId.trim() }
        val family = selected.family.toDomain()
        val canonicalMembers = selected.members.mapNotNull { member ->
            member.toDomain(selected.devices, localByDevice)
        }
        val devices = selected.devices.mapNotNull { device ->
            device.toDomain(localByDevice)
        }
        val selfMemberId = authenticatedByFamily[selected.family.id]
            ?.memberId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: devices.firstOrNull { it.deviceId == selfDeviceId }?.memberId
        val members = canonicalMembers.map { member ->
            if (member.id != selfMemberId) {
                member
            } else {
                member.copy(
                    displayName = member.displayName.trim().ifBlank {
                        localParentName?.trim().orEmpty().ifBlank { "Родитель" }
                    },
                    avatarKey = member.avatarKey
                        ?: localParentAvatar?.trim()?.takeIf(String::isNotBlank)
                )
            }
        }

        ParentFamilyDirectoryResult(
            directory = FamilyDirectoryAssembler.assemble(
                family = family,
                members = members,
                devices = devices,
                selfMemberId = selfMemberId
            ),
            source = ParentFamilyDirectorySource.SERVER
        )
    }

    private suspend fun buildLocalFallback(
        localChildren: List<Child>,
        localParentName: String?,
        localParentAvatar: String?
    ): FamilyDirectorySnapshot {
        val context = contextProvider.current()
        val selfDeviceId = context?.selfDeviceId.orEmpty()
            .ifBlank { contextResolver.resolveOwnParentId().trim() }
            .ifBlank { "parent-local" }
        val selfMemberId = context?.selfMemberId.orEmpty()
            .ifBlank { StableContextIds.memberId(selfDeviceId) }
        val serverUrl = context?.serverUrl.orEmpty()
            .ifBlank { contextResolver.resolveServerUrl().trim() }
        val allDeviceIds = buildList {
            add(selfDeviceId)
            addAll(localChildren.map(Child::deviceId))
        }
        val familyId = context?.familyId.orEmpty().ifBlank {
            StableContextIds.familyId(serverUrl, allDeviceIds)
        }
        val now = System.currentTimeMillis()
        val family = Family(
            id = familyId,
            name = "Моя семья",
            createdAt = now,
            updatedAt = now
        )

        val parentName = localParentName?.trim()
            ?.takeIf(String::isNotBlank)
            ?: "Родитель"
        val members = buildList {
            add(
                FamilyMember(
                    id = selfMemberId,
                    familyId = familyId,
                    displayName = parentName,
                    role = FamilyRole.PARENT,
                    avatarKey = localParentAvatar?.trim()?.takeIf(String::isNotBlank)
                )
            )
            localChildren.filter(Child::isActive).forEach { child ->
                add(
                    FamilyMember(
                        id = StableContextIds.memberId(child.deviceId),
                        familyId = familyId,
                        displayName = child.name.trim().ifBlank { "Ребёнок" },
                        role = child.role.toFamilyRole(),
                        avatarKey = child.avatarUrl,
                        isActive = true
                    )
                )
            }
        }
        val devices = buildList {
            add(
                FamilyDevice(
                    id = "local-$selfDeviceId",
                    familyId = familyId,
                    memberId = selfMemberId,
                    deviceId = selfDeviceId,
                    displayName = "Этот телефон",
                    isActive = true
                )
            )
            localChildren.filter(Child::isActive).forEach { child ->
                add(
                    FamilyDevice(
                        id = "local-${child.deviceId}",
                        familyId = familyId,
                        memberId = StableContextIds.memberId(child.deviceId),
                        deviceId = child.deviceId,
                        displayName = child.alias?.trim().takeUnless { it.isNullOrBlank() }
                            ?: "Телефон ребёнка",
                        lastSeenAt = child.lastSeenAt,
                        isActive = true
                    )
                )
            }
        }

        return FamilyDirectoryAssembler.assemble(
            family = family,
            members = members,
            devices = devices,
            selfMemberId = selfMemberId
        )
    }

    private fun rememberCanonicalIdentity(directory: FamilyDirectorySnapshot) {
        val currentTarget = contextResolver.resolveTargetDeviceId()
        val focusedMemberId = directory.personByDeviceId(currentTarget)?.member?.id
        contextProvider.updateFamilyIdentity(
            familyId = directory.family.id,
            selfMemberId = directory.selfMemberId,
            focusedMemberId = focusedMemberId
        )
    }

    private data class ServerDirectoryCandidate(
        val family: FamilySummaryData,
        val members: List<FamilyMemberData>,
        val devices: List<FamilyDeviceData>
    ) {
        fun matchScore(currentFamilyId: String, selfDeviceId: String, targetDeviceId: String): Int {
            var score = 0
            if (family.id == currentFamilyId) score += 100
            if (devices.any { it.deviceId == selfDeviceId }) score += 20
            if (devices.any { it.deviceId == targetDeviceId }) score += 10
            return score
        }
    }

    private fun FamilySummaryData.toDomain() = Family(
        id = id.trim(),
        name = name.trim().ifBlank { "Моя семья" },
        createdAt = FamilyDirectoryAssembler.epochMillis(createdAt) ?: 0L,
        updatedAt = FamilyDirectoryAssembler.epochMillis(updatedAt) ?: 0L
    )

    private fun FamilyMemberData.toDomain(
        devices: List<FamilyDeviceData>,
        localByDevice: Map<String, Child>
    ): FamilyMember? {
        val normalizedId = id.trim()
        val normalizedFamilyId = familyId.trim()
        if (normalizedId.isBlank() || normalizedFamilyId.isBlank()) return null
        val linkedDevices = devices.filter { it.memberId == normalizedId }
        val hasExplicitBinding = linkedDevices.any {
            it.memberBindingSource.equals("EXPLICIT", ignoreCase = true)
        }
        val localName = linkedDevices.asSequence()
            .mapNotNull { localByDevice[it.deviceId]?.name?.trim() }
            .firstOrNull(String::isNotBlank)
        val localPersonalAvatar = linkedDevices.asSequence()
            .mapNotNull { localByDevice[it.deviceId]?.avatarUrl?.trim() }
            .firstOrNull { it.startsWith("content://") }
        val canonicalName = displayName.trim()
        val resolvedName = if (!hasExplicitBinding && !localName.isNullOrBlank()) {
            localName
        } else {
            canonicalName.ifBlank { localName ?: "Участник семьи" }
        }
        return FamilyMember(
            id = normalizedId,
            familyId = normalizedFamilyId,
            displayName = resolvedName,
            role = role.toFamilyRole(),
            avatarKey = localPersonalAvatar
                ?: avatarKey?.trim()?.takeIf(String::isNotBlank),
            isActive = isActive != 0
        )
    }

    private fun FamilyDeviceData.toDomain(localByDevice: Map<String, Child>): FamilyDevice? {
        val normalizedDeviceId = deviceId.trim()
        if (normalizedDeviceId.isBlank() || memberId.isBlank() || familyId.isBlank()) return null
        val localAlias = localByDevice[normalizedDeviceId]?.alias?.trim()?.takeIf(String::isNotBlank)
        return FamilyDevice(
            id = id.trim().ifBlank { "family-device-$normalizedDeviceId" },
            familyId = familyId.trim(),
            memberId = memberId.trim(),
            deviceId = normalizedDeviceId,
            displayName = localAlias ?: displayName.trim().ifBlank { "Android-устройство" },
            platform = platform?.trim().takeUnless { it.isNullOrBlank() } ?: "android",
            lastSeenAt = FamilyDirectoryAssembler.epochMillis(lastSeenAt),
            isActive = isActive != 0
        )
    }

    private fun String.toFamilyRole(): FamilyRole = when (trim().lowercase()) {
        "parent" -> FamilyRole.PARENT
        "guardian", "relative" -> FamilyRole.GUARDIAN
        else -> FamilyRole.CHILD
    }

    companion object {
        private const val TAG = "ParentFamilyDirectory"
    }
}
