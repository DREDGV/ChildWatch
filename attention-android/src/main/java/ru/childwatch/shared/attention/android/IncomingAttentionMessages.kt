package ru.childwatch.shared.attention.android

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import ru.childwatch.shared.attention.AttentionSignalRequest

/** Local inbox for received signal messages. A timer starts when its dialog first opens. */
object IncomingAttentionMessages {
    private const val PREFS = "incoming_attention_messages"
    internal const val NOTIFICATION_ID = 1

    private fun Context.inbox() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun scope(server: String, family: String?, device: String): String? =
        if (server.isBlank() || family.isNullOrBlank() || device.isBlank()) null
        else org.json.JSONArray(listOf(server.trim().trimEnd('/'), family, device)).toString() + "/"

    internal fun save(context: Context, scope: String, request: AttentionSignalRequest): String? {
        val note = request.note ?: return null
        val key = scope + request.requestId
        val inbox = context.inbox()
        if (!inbox.contains(key)) {
            inbox.edit().putString(key, JSONObject().apply {
                put("sender", request.requesterDisplayName)
                put("text", note)
                put("seconds", request.noteDurationSeconds)
                put("receivedAt", System.currentTimeMillis())
            }.toString()).apply()
        }
        return key
    }

    fun bind(activity: ComponentActivity, currentScope: () -> String?) {
        val inbox = activity.inbox()
        val manager = activity.getSystemService(NotificationManager::class.java)
        val handler = Handler(Looper.getMainLooper())
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver,
            SharedPreferences.OnSharedPreferenceChangeListener {
            private var dialog: AlertDialog? = null
            private var timeout: Runnable? = null
            private var resumed = false

            override fun onResume(owner: LifecycleOwner) {
                resumed = true
                inbox.registerOnSharedPreferenceChangeListener(this)
                showNext()
            }

            override fun onPause(owner: LifecycleOwner) {
                resumed = false
                inbox.unregisterOnSharedPreferenceChangeListener(this)
                closeDialog()
            }

            override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
                showNext()
            }

            private fun closeDialog() {
                timeout?.let(handler::removeCallbacks)
                timeout = null
                dialog?.dismiss()
                dialog = null
            }

            private fun forget(key: String) {
                manager.cancel(key, NOTIFICATION_ID)
                inbox.edit().remove(key).apply()
            }

            private fun showNext() {
                if (!resumed || dialog != null || activity.isFinishing) return
                val scope = currentScope() ?: return
                val now = System.currentTimeMillis()
                val messages = inbox.all.entries.asSequence()
                    .filter { it.key.startsWith(scope) }
                    .mapNotNull { (key, value) ->
                        runCatching { key to JSONObject(value as String) }.getOrNull()
                    }
                    .sortedBy { (_, value) -> value.optLong("receivedAt") }
                    .toList()
                for ((key, message) in messages) {
                    val deadline = message.optLong("deadline")
                    if (deadline in 1..now) {
                        // Preference callbacks are synchronous on the main thread.
                        // The posted refresh will select the next unread message once.
                        handler.post { forget(key); showNext() }
                        return
                    }
                    val seconds = message.optInt("seconds")
                    val remaining = when {
                        deadline > now -> deadline - now
                        seconds > 0 -> seconds * 1_000L
                        else -> 0L
                    }
                    dialog = MaterialAlertDialogBuilder(activity)
                        .setTitle("Сигнал от ${message.optString("sender")}")
                        .setMessage(message.optString("text"))
                        .setCancelable(false)
                        .setPositiveButton("Прочитано", null)
                        .create().apply {
                            show()
                            getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { finish(key) }
                        }
                    if (remaining > 0) {
                        if (deadline == 0L) {
                            message.put("deadline", now + remaining)
                            inbox.edit().putString(key, message.toString()).apply()
                        }
                        timeout = Runnable { finish(key) }.also { handler.postDelayed(it, remaining) }
                    }
                    return
                }
            }

            private fun finish(key: String) {
                forget(key)
                closeDialog()
                showNext()
            }
        })
    }
}
