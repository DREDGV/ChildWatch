package ru.example.childwatch.profile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.childwatch.shared.family.FamilyDirectorySnapshot
import ru.example.childwatch.network.FamilyMemberData

/** Offline presentation only: never use cached roles to authorize an operation. */
class ParentFamilyProfileCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
    private val resolver = ParentEffectiveContextResolver(context.applicationContext)
    data class Person(val name: String, val avatar: String?)
    private fun scope() = JSONArray(listOf(resolver.resolveServerUrl(), resolver.resolveFamilyId(), resolver.resolveOwnParentId())).toString()
    fun revision(): Long = prefs.getLong("canonical_profile_revision", 0L)
    private fun payload(): JSONArray {
        if (prefs.getString("canonical_people_scope", null) != scope()) return JSONArray()
        return runCatching { JSONArray(prefs.getString("canonical_people_json", "[]")) }.getOrDefault(JSONArray())
    }
    fun person(deviceId: String): Person? {
        if (deviceId.isBlank()) return null
        val people = payload()
        for (index in 0 until people.length()) {
            val person = people.optJSONObject(index) ?: continue
            val devices = person.optJSONArray("devices") ?: continue
            if ((0 until devices.length()).any { devices.optString(it) == deviceId }) {
                val name = person.optString("name").trim().takeIf { it.isNotBlank() } ?: return null
                return Person(name, person.optString("avatar").takeIf { it.isNotBlank() && it != "null" })
            }
        }
        return null
    }
    fun remember(directory: FamilyDirectorySnapshot) {
        val people = JSONArray()
        directory.people.forEach { person -> people.put(JSONObject()
            .put("id", person.member.id).put("name", person.member.displayName)
            .put("avatar", person.member.avatarKey ?: JSONObject.NULL)
            .put("devices", JSONArray(person.activeDevices.map { it.deviceId }))) }
        prefs.edit().putString("canonical_people_scope", scope()).putString("canonical_people_json", people.toString()).apply()
    }
    fun rememberPublished(member: FamilyMemberData, ownDeviceId: String) {
        val people = payload()
        var found = false
        for (index in 0 until people.length()) {
            val person = people.optJSONObject(index) ?: continue
            if (person.optString("id") == member.id) {
                person.put("name", member.displayName).put("avatar", member.avatarKey ?: JSONObject.NULL)
                found = true
            }
        }
        if (!found) people.put(JSONObject().put("id", member.id).put("name", member.displayName)
            .put("avatar", member.avatarKey ?: JSONObject.NULL).put("devices", JSONArray(listOf(ownDeviceId))))
        prefs.edit().putLong("canonical_profile_revision", revision() + 1L)
            .putString("canonical_people_scope", scope()).putString("canonical_people_json", people.toString()).apply()
    }
}
