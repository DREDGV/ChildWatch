package ru.childwatch.shared.family

interface ActiveContextStore {
    fun read(): ActiveContext?
    fun write(context: ActiveContext)
}

class ActiveContextMigration(
    private val store: ActiveContextStore,
    private val legacyCandidates: () -> List<ActiveContextCandidate>,
    private val resolver: ActiveContextResolver = ActiveContextResolver()
) {
    fun migrateIfNeeded(): ActiveContext? {
        store.read()?.let { return it }
        return resolver.resolve(legacyCandidates())?.also(store::write)
    }
}
