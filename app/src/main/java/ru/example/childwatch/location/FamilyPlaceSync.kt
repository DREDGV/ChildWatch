package ru.example.childwatch.location

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.example.childwatch.DualLocationMapActivity
import ru.example.childwatch.R
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.profile.ParentEffectiveContextResolver

/** Durable per-phone delivery for the current adult, independent of the watched child. */
object FamilyPlaceSync {
    private val lock = Mutex()
    private var lastScope: String? = null
    private var lastAttempt = 0L
    suspend fun sync(context: Context, force: Boolean = false) = lock.withLock {
        val app = context.applicationContext
        val resolver = ParentEffectiveContextResolver(app)
        val family = resolver.resolveFamilyId()?.takeIf { it.isNotBlank() } ?: return@withLock
        val own = resolver.resolveOwnParentId()
        val server = resolver.resolveServerUrl()
        if (family.isBlank() || own.isBlank() || server.isBlank()) return@withLock
        val scope = org.json.JSONArray(listOf(server, family, own)).toString()
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && scope == lastScope && now - lastAttempt < 25_000L) return@withLock
        lastScope = scope; lastAttempt = now
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("family_places", "Семейные места", NotificationManager.IMPORTANCE_DEFAULT))
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled() ||
            manager.getNotificationChannel("family_places")?.importance == NotificationManager.IMPORTANCE_NONE ||
            (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED))
            return@withLock // Keep the cursor so events can be delivered after enabling notifications.
        val prefs = app.getSharedPreferences("family_place_delivery", Context.MODE_PRIVATE)
        val network = NetworkClient(app)
        try {
            // Bounded catch-up; remaining events are delivered at the next scheduled run.
            repeat(3) {
                val cursor = prefs.getLong(scope, 0L)
                val response = network.familyPlacesRequest(family, suffix = "/events", after = cursor)
                if (resolver.resolveFamilyId() != family || resolver.resolveOwnParentId() != own || resolver.resolveServerUrl() != server) return@withLock
                val events = response.optJSONArray("events") ?: org.json.JSONArray()
                for (index in 0 until events.length()) {
                    val event = events.getJSONObject(index)
                    val id = event.getLong("id")
                    val message = app.getString(if (event.getString("transition") == "ENTER") R.string.family_place_arrived else R.string.family_place_left,
                        event.getString("person_name"), event.getString("place_name"))
                    val intent = DualLocationMapActivity.createIntent(app, "parent", own, event.getString("device_id"))
                    val pending = PendingIntent.getActivity(app, id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val notification = NotificationCompat.Builder(app, "family_places")
                        .setSmallIcon(R.drawable.ic_notification).setContentTitle(app.getString(R.string.family_places_title))
                        .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
                        .setWhen(event.getLong("measured_at")).setShowWhen(true).setContentIntent(pending)
                        .setOnlyAlertOnce(true).setAutoCancel(true).build()
                    manager.notify("family_place:" + scope, id.toInt(), notification)
                    if (!prefs.edit().putLong(scope, id).commit()) return@withLock
                }
                val next = response.optLong("cursor", cursor)
                if (!prefs.edit().putLong(scope, next).commit() || next == cursor) return@withLock
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { android.util.Log.w("FamilyPlaceSync", "Place delivery unavailable", error) }
    }
}
