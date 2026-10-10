package ru.example.parentwatch.nearby

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.childwatch.shared.nearby.NearbySignalsSnapshot
import ru.childwatch.shared.nearby.NearbySignalsPolicy
import ru.childwatch.shared.nearby.NearbySourceState
import ru.childwatch.shared.family.ActiveContext
import android.os.SystemClock
import ru.example.parentwatch.session.ChildEffectiveContextProvider
import java.security.MessageDigest

/** Local experiment only: no transport, snapshot persistence, boot handler or remote grant. */
class NearbyProbeViewModel(application: Application) : AndroidViewModel(application) {
    data class State(val running: Boolean = false, val snapshot: NearbySignalsSnapshot? = null,
        val failed: Boolean = false, val cancelled: Boolean = false, val nextScanElapsedMs: Long = 0)
    private val app = application
    private val prefs = app.getSharedPreferences("nearby_local_probe", 0)
    private val scanner = NearbySignalsScanner(app)
    private val provider = ChildEffectiveContextProvider.get(app)
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scope = scopeKey()
    private var invalidated = false
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        modelScope.launch {
            provider.observe().collect { context ->
                if (scope != scopeKey(context)) {
                    invalidated = true
                    cancel()
                    mutableState.value = State()
                }
            }
        }
    }

    private fun scopeKey(context: ActiveContext? = provider.current()): String? {
        val current = context ?: return null
        val identity = com.google.gson.Gson().toJson(listOf(current.serverUrl, current.familyId,
            current.selfMemberId, current.selfDeviceId))
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    fun current(): Boolean = !invalidated && scope != null && scope == scopeKey()
    fun allowed(): Boolean = current() && prefs.getBoolean("local:$scope", false)
    fun allow(value: Boolean) {
        if (!current()) { cancel(); return }
        prefs.edit().putBoolean("local:$scope", value).apply()
        if (!value) { cancel(); mutableState.value = State(nextScanElapsedMs = mutableState.value.nextScanElapsedMs) }
    }
    fun start(activeWifi: Boolean) {
        if (!allowed() || job?.isActive == true || mutableState.value.nextScanElapsedMs > SystemClock.elapsedRealtime()) return
        val previous = mutableState.value.snapshot
        mutableState.value = State(running = true, snapshot = previous,
            nextScanElapsedMs = SystemClock.elapsedRealtime() + NearbySignalsPolicy.COOLDOWN_MS)
        // Assign before starting even if the scanner returns synchronously (e.g. cooldown).
        job = modelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val snapshot = scanner.scan(optIn = true, allowActiveWifi = activeWifi)
                if (allowed()) mutableState.value = State(
                    snapshot = if (snapshot.reports.all { it.state == NearbySourceState.COOLDOWN } && previous != null) previous else snapshot,
                    nextScanElapsedMs = snapshot.capturedElapsedMs + snapshot.cooldownRemainingMs)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (allowed()) mutableState.value = mutableState.value.copy(running = false, failed = true) }
            finally {
                if (job == kotlinx.coroutines.currentCoroutineContext()[Job]) {
                    job = null
                    if (mutableState.value.running) mutableState.value = mutableState.value.copy(running = false, cancelled = true)
                }
            }
        }
        job?.start()
    }
    fun cancel() {
        job?.cancel(); job = null
        if (mutableState.value.running) mutableState.value = mutableState.value.copy(running = false, cancelled = true)
    }
    override fun onCleared() { cancel(); modelScope.cancel(); super.onCleared() }
}
