package ru.example.parentwatch.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Sends diagnostic events to the backend so we can inspect them from Railway logs.
 * Intended for use on the child (ParentWatch) application where adb logcat might be unavailable.
 */
object RemoteLogger {

    private const val TAG = "RemoteLogger"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun info(serverUrl: String?, deviceId: String?, source: String, message: String, meta: Map<String, Any?>? = null) {
        send("INFO", serverUrl, deviceId, source, message, meta, null)
    }

    fun warn(serverUrl: String?, deviceId: String?, source: String, message: String, meta: Map<String, Any?>? = null) {
        send("WARN", serverUrl, deviceId, source, message, meta, null)
    }

    fun error(
        serverUrl: String?,
        deviceId: String?,
        source: String,
        message: String,
        throwable: Throwable? = null,
        meta: Map<String, Any?>? = null
    ) {
        send("ERROR", serverUrl, deviceId, source, message, meta, throwable)
    }

    private fun send(
        level: String,
        serverUrl: String?,
        deviceId: String?,
        source: String,
        message: String,
        meta: Map<String, Any?>?,
        throwable: Throwable?
    ) {
        if (serverUrl.isNullOrBlank()) {
            Log.w(TAG, "Skipping remote log (missing server URL): $message")
            return
        }

        val trimmedUrl = serverUrl.trimEnd('/')
        val requestUrl = "$trimmedUrl/api/debug/log"

        val payload = JSONObject().apply {
            put("deviceId", deviceId ?: "unknown-device")
            put("source", source)
            put("level", level)
            put("message", message)
            if (meta != null && meta.isNotEmpty()) {
                put("meta", JSONObject(meta))
            }
            throwable?.let {
                put(
                    "error",
                    JSONObject(
                        mapOf(
                            "type" to it::class.java.simpleName,
                            "message" to (it.message ?: "unknown"),
                            "stackTrace" to it.stackTraceToString()
                        )
                    )
                )
            }
        }

        scope.launch {
            try {
                val request = Request.Builder()
                    .url(requestUrl)
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Remote log failed with status ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send remote log: ${e.message}", e)
            }
        }
    }
}
