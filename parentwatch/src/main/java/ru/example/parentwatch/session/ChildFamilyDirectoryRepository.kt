package ru.example.parentwatch.session

import android.content.Context
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import ru.childwatch.shared.family.Family
import ru.childwatch.shared.family.FamilyDevice
import ru.childwatch.shared.family.FamilyDirectoryAssembler
import ru.childwatch.shared.family.FamilyDirectorySnapshot
import ru.childwatch.shared.family.FamilyMember
import ru.childwatch.shared.family.FamilyRole
import ru.example.parentwatch.network.FamilyDeviceData
import ru.example.parentwatch.network.FamilyMemberData
import ru.example.parentwatch.network.NetworkClient

/**
 * Canonical family directory for ChildDevice.
 *
 * A family member is the person shown in UI; a device id is only the routing
 * endpoint owned by that person.  The last verified server snapshot is kept
 * as a bounded offline cache so screens never have to invent people from
 * technical device identifiers.
 */
class ChildFamilyDirectoryRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val networkClient by lazy { NetworkClient(appContext) }
    private val contextResolver by lazy { ChildEffectiveContextResolver(appContext) }
    private val contextProvider by lazy { ChildEffectiveContextProvider.get(appContext) }

    suspend fun refresh(): FamilyDirectorySnapshot? = coroutineScope {
        val identityResponse = runCatching { networkClient.getAuthenticatedIdentity() }
            .onFailure { Log.w(TAG, "Family identity request failed", it) }
            .getOrNull()
        val identity = identityResponse
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.takeIf { it.success }
            ?: return@coroutineScope loadCached()

        val ownDeviceId = contextResolver.resolveChildDeviceId().trim()
        val preferredFamilyId = contextResolver.resolveFamilyId().orEmpty().trim()
        val membership = identity.memberships
            .asSequence()
            .filter { it.member.isActive }
            .sortedByDescending { candidate ->
                (if (candidate.familyId == preferredFamilyId) 100 else 0) +
                    (if (candidate.binding.deviceId == ownDeviceId) 20 else 0)
            }
            .firstOrNull()
            ?: return@coroutineScope loadCached()

        val membersDeferred = async { networkClient.getFamilyMembers(membership.familyId) }
        val devicesDeferred = async { networkClient.getFamilyDevices(membership.familyId) }
        val membersResponse = membersDeferred.await()
        val devicesResponse = devicesDeferred.await()
        if (!membersResponse.isSuccessful || !devicesResponse.isSuccessful) {
            return@coroutineScope loadCached()
        }

        val members = membersResponse.body()?.members.orEmpty().mapNotNull(::toMember)
        val devices = devicesResponse.body()?.devices.orEmpty().mapNotNull(::toDevice)
        if (members.isEmpty() || devices.isEmpty()) return@coroutineScope loadCached()

        val family = Family(
            id = membership.family.id.trim(),
            name = membership.family.name.trim().ifBlank { "Моя семья" },
            createdAt = FamilyDirectoryAssembler.epochMillis(membership.family.createdAt) ?: 0L,
            updatedAt = FamilyDirectoryAssembler.epochMillis(membership.family.updatedAt) ?: 0L
        )
        val directory = FamilyDirectoryAssembler.assemble(
            family = family,
            members = members,
            devices = devices,
            selfMemberId = membership.memberId
        )
        if (directory.people.isEmpty()) return@coroutineScope loadCached()

        save(directory)
        val targetDeviceId = contextResolver.resolveParentDeviceId()
        contextProvider.updateFamilyIdentity(
            familyId = directory.family.id,
            selfMemberId = directory.selfMemberId,
            focusedMemberId = directory.personByDeviceId(targetDeviceId)?.member?.id
        )
        directory
    }

    fun loadCached(): FamilyDirectorySnapshot? {
        val raw = prefs.getString(KEY_DIRECTORY_JSON, null)?.takeIf(String::isNotBlank)
            ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val familyId = root.optString("familyId").trim()
            if (familyId.isBlank()) return null
            val family = Family(
                id = familyId,
                name = root.optString("familyName").trim().ifBlank { "Моя семья" },
                createdAt = root.optLong("familyCreatedAt", 0L),
                updatedAt = root.optLong("familyUpdatedAt", 0L)
            )
            val membersJson = root.optJSONArray("members") ?: JSONArray()
            val members = mutableListOf<FamilyMember>()
            val devices = mutableListOf<FamilyDevice>()
            for (index in 0 until membersJson.length()) {
                val memberJson = membersJson.optJSONObject(index) ?: continue
                val memberId = memberJson.optString("id").trim()
                val displayName = memberJson.optString("displayName").trim()
                if (memberId.isBlank() || displayName.isBlank()) continue
                members += FamilyMember(
                    id = memberId,
                    familyId = familyId,
                    displayName = displayName,
                    role = memberJson.optString("role").toFamilyRole(),
                    avatarKey = memberJson.optString("avatarKey").trim()
                        .takeIf { it.isNotBlank() && it != "null" },
                    isActive = true
                )
                val devicesJson = memberJson.optJSONArray("devices") ?: JSONArray()
                for (deviceIndex in 0 until devicesJson.length()) {
                    val deviceJson = devicesJson.optJSONObject(deviceIndex) ?: continue
                    val deviceId = deviceJson.optString("deviceId").trim()
                    if (deviceId.isBlank()) continue
                    devices += FamilyDevice(
                        id = deviceJson.optString("id").trim().ifBlank { "cached-$deviceId" },
                        familyId = familyId,
                        memberId = memberId,
                        deviceId = deviceId,
                        displayName = deviceJson.optString("displayName").trim()
                            .ifBlank { "Android-устройство" },
                        platform = deviceJson.optString("platform").trim()
                            .takeIf { it.isNotBlank() && it != "null" }
                            ?: "android",
                        lastSeenAt = deviceJson.optLong("lastSeenAt", 0L).takeIf { it > 0L },
                        isActive = true
                    )
                }
            }
            FamilyDirectoryAssembler.assemble(
                family = family,
                members = members,
                devices = devices,
                selfMemberId = root.optString("selfMemberId").trim().takeIf(String::isNotBlank),
                refreshedAt = root.optLong("refreshedAt", 0L).takeIf { it > 0L }
                    ?: System.currentTimeMillis()
            )
        }.onFailure { Log.w(TAG, "Cannot read cached family directory", it) }.getOrNull()
    }

    private fun save(directory: FamilyDirectorySnapshot) {
        val root = JSONObject().apply {
            put("familyId", directory.family.id)
            put("familyName", directory.family.name)
            put("familyCreatedAt", directory.family.createdAt)
            put("familyUpdatedAt", directory.family.updatedAt)
            put("selfMemberId", directory.selfMemberId ?: JSONObject.NULL)
            put("refreshedAt", directory.refreshedAt)
            put("members", JSONArray().apply {
                directory.people.forEach { person ->
                    put(JSONObject().apply {
                        put("id", person.member.id)
                        put("displayName", person.member.displayName)
                        put("role", person.member.role.name)
                        put("avatarKey", person.member.avatarKey ?: JSONObject.NULL)
                        put("devices", JSONArray().apply {
                            person.activeDevices.forEach { device ->
                                put(JSONObject().apply {
                                    put("id", device.id)
                                    put("deviceId", device.deviceId)
                                    put("displayName", device.displayName)
                                    put("platform", device.platform ?: JSONObject.NULL)
                                    put("lastSeenAt", device.lastSeenAt ?: 0L)
                                })
                            }
                        })
                    })
                }
            })
        }
        prefs.edit().putString(KEY_DIRECTORY_JSON, root.toString()).apply()
    }

    private fun toMember(data: FamilyMemberData): FamilyMember? {
        val id = data.id.trim()
        val familyId = data.familyId.trim()
        val displayName = data.displayName.trim()
        if (id.isBlank() || familyId.isBlank() || displayName.isBlank()) return null
        return FamilyMember(
            id = id,
            familyId = familyId,
            displayName = displayName,
            role = data.role.toFamilyRole(),
            avatarKey = data.avatarKey?.trim()?.takeIf(String::isNotBlank),
            isActive = data.isActive != 0
        )
    }

    private fun toDevice(data: FamilyDeviceData): FamilyDevice? {
        val deviceId = data.deviceId.trim()
        val memberId = data.memberId.trim()
        val familyId = data.familyId.trim()
        if (deviceId.isBlank() || memberId.isBlank() || familyId.isBlank()) return null
        return FamilyDevice(
            id = data.id.trim().ifBlank { "family-device-$deviceId" },
            familyId = familyId,
            memberId = memberId,
            deviceId = deviceId,
            displayName = data.displayName.trim().ifBlank { "Android-устройство" },
            platform = data.platform?.trim().orEmpty().ifBlank { "android" },
            lastSeenAt = FamilyDirectoryAssembler.epochMillis(data.lastSeenAt),
            isActive = data.isActive != 0
        )
    }

    private fun String.toFamilyRole(): FamilyRole = when (trim().uppercase()) {
        "PARENT" -> FamilyRole.PARENT
        "GUARDIAN", "RELATIVE" -> FamilyRole.GUARDIAN
        else -> FamilyRole.CHILD
    }

    companion object {
        private const val TAG = "ChildFamilyDirectory"
        private const val PREFS_NAME = "parentwatch_prefs"
        private const val KEY_DIRECTORY_JSON = "canonical_family_directory_snapshot_json"
    }
}
