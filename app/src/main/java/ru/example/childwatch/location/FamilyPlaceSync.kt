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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.example.childwatch.DualLocationMapActivity
import ru.example.childwatch.R
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.profile.ParentEffectiveContextResolver
import ru.childwatch.shared.family.FamilyPlaceDeliveryEvent
import ru.childwatch.shared.family.FamilyPlaceDeliveryPolicy
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Durable per-phone delivery for the current adult, independent of the watched child. */
object FamilyPlaceSync {
    enum class Outcome { COMPLETE, THROTTLED, SCOPE_UNAVAILABLE, CONTEXT_CHANGED, NOTIFICATIONS_DISABLED,
        ACCESS_DENIED, INVALID_PAGE, STORAGE_FAILED, RETRY_NEEDED, SCAN_LIMIT }
    private const val LIVE_CHANNEL = "family_places"
    private const val HISTORY_CHANNEL = "family_places_history"
    private val lock = Mutex()
    private var lastScope: String? = null
    private var lastAttempt = 0L
    private fun identity(resolver: ParentEffectiveContextResolver): List<String>? = listOf(
        resolver.resolveServerUrl().trim(), resolver.resolveFamilyId().orEmpty().trim(),
        resolver.resolveSelfMemberId().orEmpty().trim(), resolver.resolveOwnParentId().trim()
    ).takeIf { values -> values.all { it.isNotBlank() } }

    suspend fun sync(context: Context, force: Boolean = false): Outcome = lock.withLock {
        val app = context.applicationContext
        val resolver = ParentEffectiveContextResolver(app)
        val expected = identity(resolver) ?: return@withLock Outcome.SCOPE_UNAVAILABLE
        val (server, family, member, own) = expected
        val scope = org.json.JSONArray(expected).toString()
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && scope == lastScope && now - lastAttempt < 25_000L) return@withLock Outcome.THROTTLED
        lastScope = scope; lastAttempt = now
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(LIVE_CHANNEL, app.getString(R.string.family_places_title), NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(HISTORY_CHANNEL,
            app.getString(R.string.family_places_history_channel), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false)
        })
        fun allowed(channel: String? = null): Boolean = NotificationManagerCompat.from(app).areNotificationsEnabled() &&
            (channel == null || manager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE) &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(app,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        if (!allowed()) return@withLock Outcome.NOTIFICATIONS_DISABLED
        val prefs = app.getSharedPreferences("family_place_delivery", Context.MODE_PRIVATE)
        val network = NetworkClient(app)
        try {
            // Bounded catch-up; remaining events are delivered at the next scheduled run.
            repeat(3) {
                currentCoroutineContext().ensureActive()
                if (identity(resolver) != expected || network.resolveConfiguredServerUrl() != server)
                    return@withLock Outcome.CONTEXT_CHANGED
                val cursor = prefs.getLong(scope, 0L)
                val response = network.familyPlacesRequest(family, suffix = "/events", after = cursor, expectedScope = scope)
                currentCoroutineContext().ensureActive()
                if (identity(resolver) != expected) return@withLock Outcome.CONTEXT_CHANGED
                val rows = response.getJSONArray("events")
                val events = (0 until rows.length()).map { parseEvent(rows.getJSONObject(it)) }
                val plan = FamilyPlaceDeliveryPolicy.plan(cursor, integer(response, "cursor"), events,
                    family, member, System.currentTimeMillis())
                for (batch in plan.batches) {
                    currentCoroutineContext().ensureActive()
                    if (identity(resolver) != expected) return@withLock Outcome.CONTEXT_CHANGED
                    val channel = if (batch.historical) HISTORY_CHANNEL else LIVE_CHANNEL
                    if (!allowed(channel)) return@withLock Outcome.NOTIFICATIONS_DISABLED
                    val last = batch.events.last()
                    val intent = DualLocationMapActivity.createIntent(app, "parent", own, last.deviceId)
                    intent.data = android.net.Uri.Builder().scheme("childwatch").authority("family-place")
                        .appendPath(scope).appendPath(last.id.toString()).build()
                    val pending = PendingIntent.getActivity(app, last.id.toInt(), intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val lines = batch.events.map { event ->
                        val message = app.getString(if (event.transition == "ENTER") R.string.family_place_arrived else R.string.family_place_left,
                            event.personName.take(40), event.placeName.take(40))
                        app.getString(R.string.family_places_event_measured, measuredTime(event.measuredAt), message)
                    }
                    val content = if (batch.historical) app.getString(R.string.family_places_history_delivery, batch.events.size) else lines.single()
                    val details = if (batch.historical) content + "\n" + lines.joinToString("\n") else content
                    val notification = NotificationCompat.Builder(app, channel)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(app.getString(if (batch.historical) R.string.family_places_history_title else R.string.family_places_title))
                        .setContentText(content).setStyle(NotificationCompat.BigTextStyle().bigText(details))
                        .setWhen(last.measuredAt).setShowWhen(true).setContentIntent(pending)
                        .setOnlyAlertOnce(true).setAutoCancel(true).apply { if (batch.historical) setSilent(true) }.build()
                    val tag = (if (batch.historical) "family_place_history:" else "family_place:") + scope + ":" + last.id
                    manager.notify(tag, last.id.toInt(), notification)
                    // Posting before commit permits retry; IDs remain stable after partial failure.
                    if (identity(resolver) != expected) return@withLock Outcome.CONTEXT_CHANGED
                    if (!allowed(channel)) return@withLock Outcome.NOTIFICATIONS_DISABLED
                    if (!prefs.edit().putLong(scope, last.id).commit()) return@withLock Outcome.STORAGE_FAILED
                }
                currentCoroutineContext().ensureActive()
                if (identity(resolver) != expected) return@withLock Outcome.CONTEXT_CHANGED
                if (!allowed()) return@withLock Outcome.NOTIFICATIONS_DISABLED
                if (!prefs.edit().putLong(scope, plan.cursor).commit()) return@withLock Outcome.STORAGE_FAILED
                if (plan.cursor == cursor) return@withLock Outcome.COMPLETE
            }
            Outcome.SCAN_LIMIT
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: SecurityException) { Outcome.NOTIFICATIONS_DISABLED }
        catch (error: Exception) {
            val outcome = when {
                error.message == "PLACE_CONTEXT_CHANGED" -> Outcome.CONTEXT_CHANGED
                error.message == "PLACE_PERMISSION_DENIED" -> Outcome.ACCESS_DENIED
                error is IllegalArgumentException || error is org.json.JSONException -> Outcome.INVALID_PAGE
                else -> Outcome.RETRY_NEEDED
            }
            android.util.Log.w("FamilyPlaceSync", "Place delivery deferred: $outcome")
            outcome
        }
    }

    private fun integer(row: JSONObject, key: String): Long = when (val value = row.get(key)) {
        is Long -> value
        is Int -> value.toLong()
        else -> throw IllegalArgumentException("PLACE_INTEGER_INVALID")
    }
    private fun parseEvent(row: JSONObject) = FamilyPlaceDeliveryEvent(
        integer(row, "id"), row.getString("family_id"), row.getString("owner_member_id"),
        row.getString("device_id"), row.getString("watch_id"), row.getString("transition"),
        integer(row, "measured_at"), row.getString("person_name"), row.getString("place_name"))
    private fun measuredTime(timestamp: Long): String =
        SimpleDateFormat("dd.MM · HH:mm", Locale.getDefault()).format(Date(timestamp))
}
