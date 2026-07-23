package ru.example.parentwatch.session

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.childwatch.shared.family.ActiveContext
import ru.childwatch.shared.family.ActiveContextCandidate
import ru.childwatch.shared.family.ActiveContextResolver
import ru.childwatch.shared.family.ContextSource
import ru.childwatch.shared.family.EffectiveContextProvider
import ru.childwatch.shared.family.FeatureContext
import ru.childwatch.shared.family.forFeature
import ru.example.parentwatch.utils.ServerUrlResolver

class ChildEffectiveContextProvider private constructor(context: Context) : EffectiveContextProvider {
    private val appContext = context.applicationContext
    private val store = ChildContextStore(appContext)
    private val migration = ChildLegacyContextMigration(appContext)
    private val resolver = ActiveContextResolver()
    private val state = MutableStateFlow(store.read() ?: migration.migrateIfNeeded())

    override fun current(): ActiveContext? = state.value ?: refresh()

    override fun observe(): StateFlow<ActiveContext?> = state.asStateFlow()

    @Synchronized
    override fun refresh(): ActiveContext? {
        val canonical = store.read()
        val candidates = buildList {
            canonical?.let {
                add(ActiveContextCandidate.from(it).copy(source = ContextSource.CANONICAL))
            }
            addAll(migration.legacyCandidates())
        }
        return resolver.resolve(candidates)?.also(::persist)
    }

    @Synchronized
    fun updateFromActiveSession(session: ChildActiveSession): ActiveContext? {
        val existing = current()
        val normalizedSelf = session.ownChildDeviceId.trim()
        val normalizedServer = session.serverUrl.trim()
            .takeIf(String::isNotEmpty)
            ?.let(ServerUrlResolver::normalizeServerUrl)
            .orEmpty()
        val keepsIdentity = existing != null &&
            (normalizedSelf.isBlank() || normalizedSelf == existing.selfDeviceId) &&
            (normalizedServer.isBlank() || normalizedServer == existing.serverUrl)
        val fallback = existing?.let(ActiveContextCandidate::from)?.copy(
            familyId = existing.familyId.takeIf { keepsIdentity },
            selfMemberId = existing.selfMemberId.takeIf {
                normalizedSelf.isBlank() || normalizedSelf == existing.selfDeviceId
            },
            source = ContextSource.CANONICAL
        )
        val resolved = resolver.resolve(
            listOfNotNull(
                ActiveContextCandidate(
                    selfDeviceId = normalizedSelf,
                    targetDeviceId = session.linkedParentDeviceId,
                    serverUrl = normalizedServer,
                    source = ContextSource.CANONICAL,
                    updatedAt = session.updatedAt
                ),
                fallback
            )
        ) ?: return existing
        val selected = resolved.withSelection(
            focusedMemberId = existing?.focusedMemberId
                ?.takeIf { existing.targetDeviceId == session.linkedParentDeviceId.trim() },
            targetDeviceId = session.linkedParentDeviceId,
            updatedAt = session.updatedAt
        )
        persist(selected)
        return selected
    }

    @Synchronized
    fun updateSelection(focusedMemberId: String?, targetDeviceId: String?): ActiveContext? {
        val updated = current()?.withSelection(focusedMemberId, targetDeviceId) ?: return null
        persist(updated)
        return updated
    }

    /**
     * Enrich the device-scoped session with the canonical server family identity.
     * Device ids remain routing keys; member ids identify the people who own them.
     */
    @Synchronized
    fun updateFamilyIdentity(
        familyId: String?,
        selfMemberId: String?,
        focusedMemberId: String?
    ): ActiveContext? {
        val existing = current() ?: return null
        val normalizedFamilyId = familyId?.trim()?.takeIf(String::isNotBlank)
        val normalizedSelfMemberId = selfMemberId?.trim()?.takeIf(String::isNotBlank)
        val normalizedFocusedMemberId = focusedMemberId?.trim()?.takeIf(String::isNotBlank)
            ?.takeIf { !existing.targetDeviceId.isNullOrBlank() }
        val updated = existing.copy(
            familyId = normalizedFamilyId ?: existing.familyId,
            selfMemberId = normalizedSelfMemberId ?: existing.selfMemberId,
            focusedMemberId = normalizedFocusedMemberId ?: existing.focusedMemberId,
            source = ContextSource.CANONICAL,
            updatedAt = System.currentTimeMillis()
        )
        persist(updated)
        return updated
    }

    fun storageNamespace(feature: String): String? {
        return current()?.storageNamespace(OWNER_SCOPE, feature)
    }

    fun featureContext(feature: String): FeatureContext? {
        return current()?.forFeature(OWNER_SCOPE, feature)
    }

    private fun persist(context: ActiveContext) {
        if (store.read() != context) {
            store.write(context)
        }
        state.value = context
    }

    companion object {
        private const val OWNER_SCOPE = "child"

        @Volatile
        private var instance: ChildEffectiveContextProvider? = null

        fun get(context: Context): ChildEffectiveContextProvider {
            return instance ?: synchronized(this) {
                instance ?: ChildEffectiveContextProvider(context).also { instance = it }
            }
        }
    }
}
