package ru.example.childwatch.profile

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.example.childwatch.utils.ParentMonitorProfileManager

/** One own-person flow for home and settings; watching a child never changes its recipient. */
object OwnProfileEditor {
    private val opening = java.util.WeakHashMap<ComponentActivity, Boolean>()
    fun show(activity: ComponentActivity, onStored: () -> Unit) {
        if (opening[activity] == true || ru.example.childwatch.designsystem.FamilyProfileEditor.isOpen(activity)) return
        opening[activity] = true
        activity.lifecycleScope.launch {
            try {
                val repository = ParentFamilyDirectoryRepository(activity)
                val resolver = ParentEffectiveContextResolver(activity)
                val profiles = ParentMonitorProfileManager(activity)
                val profile = profiles.getActiveProfile()
                val ownId = resolver.resolveOwnParentId()
                val familyId = resolver.resolveFamilyId()
                val serverUrl = resolver.resolveServerUrl()
                val stored = runCatching { repository.loadOwnProfile() }.getOrNull()
                val cached = if (stored == null) runCatching {
                    val directory = repository.load().directory
                    repository.ownPerson(directory, resolver.resolveSelfMemberId(), ownId)?.member
                }.getOrNull() else null
                if (activity.isFinishing || activity.isDestroyed ||
                    resolver.resolveOwnParentId() != ownId || resolver.resolveFamilyId() != familyId ||
                    resolver.resolveServerUrl() != serverUrl) return@launch
                val initialName = stored?.first ?: cached?.displayName
                    ?: ParentParticipantNameResolver(activity).resolveOwnParentDisplayName()
                val avatar = stored?.second ?: cached?.avatarKey
                ProfileEditDialog.show(activity, initialName, avatar, ProfilePhotoSession(activity, activity),
                    org.json.JSONArray(listOf(serverUrl, familyId, ownId)).toString()) { result ->
                    if (resolver.resolveOwnParentId() != ownId || resolver.resolveFamilyId() != familyId ||
                        resolver.resolveServerUrl() != serverUrl) {
                        false
                    } else {
                        val published = try {
                            repository.updateOwnProfile(result.name, result.avatarKey)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            android.util.Log.w("OwnProfileEditor", "Profile publication failed", error)
                            false
                        }
                        if (resolver.resolveOwnParentId() != ownId || resolver.resolveFamilyId() != familyId ||
                            resolver.resolveServerUrl() != serverUrl) return@show false
                        if (published) {
                            activity.getSharedPreferences("childwatch_prefs", android.content.Context.MODE_PRIVATE)
                                .edit().putString(ParentParticipantNameResolver.KEY_SELF_DISPLAY_NAME, result.name).apply()
                            // A saved connection is not another person. Update only the same active record.
                            if (profile != null && profiles.getActiveProfileId() == profile.id) {
                                profiles.saveProfile(profile.copy(name = result.name, updatedAt = System.currentTimeMillis()))
                            }
                            runCatching { onStored() }
                                .onFailure { android.util.Log.w("OwnProfileEditor", "Accepted profile UI refresh failed", it) }
                        }
                        published
                    }
                }
            } finally { opening.remove(activity) }
        }
    }
}
