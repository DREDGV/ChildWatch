package ru.example.parentwatch.games

import android.content.Context
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import ru.example.parentwatch.session.ChildEffectiveContextResolver
import ru.example.parentwatch.network.NetworkClient
import ru.example.childwatch.designsystem.GameOnlineBridge

/** Captures the phone owner, never the person selected on the map. */
class FamilyGamesHost private constructor(context: Context) : GameOnlineBridge.Host {
    private val resolver = ChildEffectiveContextResolver(context.applicationContext)
    private fun identity() = listOf(resolver.resolveServerUrl().trim().trimEnd('/'),
        resolver.resolveFamilyId().orEmpty(), resolver.resolveSelfMemberId().orEmpty(),
        resolver.resolveChildDeviceId())
    private val captured = identity().toList()
    private val network by lazy { NetworkClient(context.applicationContext) }

    override fun scopeKey(): String = if (captured.any(String::isBlank) || identity() != captured) "" else JSONArray(captured).toString()

    override fun request(method: String, path: String, body: JSONObject?): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper()) { "GAME_BACKGROUND_REQUIRED" }
        if (captured.any(String::isBlank) || identity() != captured)
            throw GameOnlineBridge.Failure(403, "GAME_CONTEXT_CHANGED")
        val result = network.familyGamesRequest(captured, method, path, body)
        if (identity() != captured) throw GameOnlineBridge.Failure(403, "GAME_CONTEXT_CHANGED")
        return result
    }

    override fun snakeCredentials(): ru.example.childwatch.designsystem.SnakeRealtimeBridge.Credentials {
        check(Looper.myLooper() != Looper.getMainLooper()) { "GAME_BACKGROUND_REQUIRED" }
        if (scopeKey().isEmpty()) throw GameOnlineBridge.Failure(403, "GAME_CONTEXT_CHANGED")
        val credentials = network.familySnakeCredentials(captured)
        if (scopeKey().isEmpty()) throw GameOnlineBridge.Failure(403, "GAME_CONTEXT_CHANGED")
        return credentials
    }

    override fun arcanoidCredentials(): ru.example.childwatch.designsystem.SnakeRealtimeBridge.Credentials {
        check(Looper.myLooper() != Looper.getMainLooper()) { "GAME_BACKGROUND_REQUIRED" }
        if (scopeKey().isEmpty()) throw GameOnlineBridge.Failure(403, "GAME_CONTEXT_CHANGED")
        val credentials = network.familyArcanoidCredentials(captured)
        if (scopeKey().isEmpty()) throw GameOnlineBridge.Failure(403, "GAME_CONTEXT_CHANGED")
        return credentials
    }

    companion object {
        fun create(context: Context): GameOnlineBridge.Host = FamilyGamesHost(context.applicationContext)
        fun register(context: Context) {
            GameOnlineBridge.host = create(context)
        }
    }
}
