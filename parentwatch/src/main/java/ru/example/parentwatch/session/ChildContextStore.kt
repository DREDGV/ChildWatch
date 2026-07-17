package ru.example.parentwatch.session

import android.content.Context
import ru.childwatch.shared.family.ActiveContext
import ru.childwatch.shared.family.ActiveContextCodec
import ru.childwatch.shared.family.ActiveContextStore

class ChildContextStore(context: Context) : ActiveContextStore {
    companion object {
        private const val PREFS_NAME = "parentwatch_active_context"
        private const val KEY_ACTIVE_CONTEXT = "active_context_v1"
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): ActiveContext? {
        return prefs.getString(KEY_ACTIVE_CONTEXT, null)
            ?.let(ActiveContextCodec::decode)
    }

    override fun write(context: ActiveContext) {
        prefs.edit()
            .putString(KEY_ACTIVE_CONTEXT, ActiveContextCodec.encode(context))
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_ACTIVE_CONTEXT).apply()
    }
}
