package ru.example.childwatch.location

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.profile.ParentEffectiveContextResolver
import ru.example.childwatch.designsystem.FamilyPickupNotifications

/** Same member-scoped feed used by the screen; no selected-child or legacy-link authorization. */
object FamilyPickupSync {
    private val lock = Mutex()
    private var previousScope = ""
    private var lastAttempt = 0L
    suspend fun sync(context: Context) = lock.withLock {
        val app = context.applicationContext
        val resolver = ParentEffectiveContextResolver(app)
        fun scope(): String {
            val family = resolver.resolveFamilyId()?.takeIf { it.isNotBlank() } ?: return ""
            val member = resolver.resolveSelfMemberId()?.takeIf { it.isNotBlank() } ?: return ""
            val server = resolver.resolveServerUrl().takeIf { it.isNotBlank() } ?: return ""
            val own = resolver.resolveOwnParentId().takeIf { it.isNotBlank() } ?: return ""
            return JSONArray(listOf(server, family, member, own)).toString()
        }
        val expected = scope()
        val scopePrefs = app.getSharedPreferences("family_pickup_sync_scope", Context.MODE_PRIVATE)
        if (previousScope.isBlank()) previousScope = scopePrefs.getString("last_scope", "").orEmpty()
        if (expected != previousScope) {
            if (previousScope.isNotBlank()) FamilyPickupNotifications.clear(app, previousScope)
            previousScope = expected; lastAttempt = 0
            scopePrefs.edit().putString("last_scope", expected).commit()
        }
        if (expected.isBlank()) return@withLock
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAttempt < 25_000L) return@withLock
        lastAttempt = now
        try {
            val parts = JSONArray(expected)
            val response = NetworkClient(app).pickupRequest(parts.getString(1), expectedScope = expected)
            if (expected != scope() || response.getJSONObject("actor").getString("memberId") != parts.getString(2)) return@withLock
            val intent = ru.example.childwatch.DualLocationMapActivity.createIntent(app, "parent", resolver.resolveOwnParentId(), resolver.resolveFocusedChildId())
            FamilyPickupNotifications.deliver(app, expected, response, intent, ru.example.childwatch.R.drawable.ic_notification)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (failure.message?.startsWith("PICKUP_HTTP_401") == true || failure.message?.startsWith("PICKUP_HTTP_403") == true)
                FamilyPickupNotifications.clear(app, expected)
            android.util.Log.w("FamilyPickupSync", "Pickup refresh unavailable")
        }
    }
}
