package ru.example.childwatch.profile

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
import ru.childwatch.shared.family.FeatureTargetResolver
import ru.childwatch.shared.family.FeatureTargetResult
import ru.childwatch.shared.family.forFeature

class ParentEffectiveContextProvider private constructor(context: Context) : EffectiveContextProvider {
    private val appContext = context.applicationContext
    private val store = ParentContextStore(appContext)
    private val migration = ParentLegacyContextMigration(appContext)
    private val resolver = ActiveContextResolver()
    private val featureTargetResolver = FeatureTargetResolver()
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
    fun updateFromActiveSession(session: ParentActiveSession): ActiveContext? {
        val existing = current()
        val normalizedSelf = session.ownParentDeviceId.trim()
        val normalizedServer = session.serverUrl.trim()
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
                    targetDeviceId = session.linkedChildDeviceId,
                    serverUrl = normalizedServer,
                    source = ContextSource.CANONICAL,
                    updatedAt = session.updatedAt
                ),
                fallback
            )
        ) ?: return existing
        val selected = resolved.withSelection(
            focusedMemberId = existing?.focusedMemberId
                ?.takeIf { existing.targetDeviceId == session.linkedChildDeviceId.trim() },
            targetDeviceId = session.linkedChildDeviceId,
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
     * Enrich the current device selection with canonical server identity.
     * This method deliberately cannot change targetDeviceId.
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
            ?.takeIf { existing.targetDeviceId != null }
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

    /** Resolve one immutable feature target without changing global selection. */
    fun resolveFeatureTarget(
        feature: String,
        explicitTargetDeviceId: String? = null,
        explicitFocusedMemberId: String? = null
    ): FeatureTargetResult {
        return featureTargetResolver.resolve(
            activeContext = current(),
            ownerScope = OWNER_SCOPE,
            feature = feature,
            explicitTargetDeviceId = explicitTargetDeviceId,
            explicitFocusedMemberId = explicitFocusedMemberId
        )
    }

    private fun persist(context: ActiveContext) {
        if (store.read() != context) {
            store.write(context)
        }
        state.value = context
    }

    companion object {
        private const val OWNER_SCOPE = "parent"

        @Volatile
        private var instance: ParentEffectiveContextProvider? = null

        fun get(context: Context): ParentEffectiveContextProvider {
            return instance ?: synchronized(this) {
                instance ?: ParentEffectiveContextProvider(context).also { instance = it }
            }
        }
    }
}
