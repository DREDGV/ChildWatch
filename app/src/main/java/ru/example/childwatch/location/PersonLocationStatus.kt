package ru.example.childwatch.location

import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.profile.ParentEffectiveContextResolver

/** One location line for a person screen; runs only while the screen is visible. */
class PersonLocationStatus(
    private val activity: ComponentActivity,
    private val view: TextView,
    private val network: NetworkClient,
    private val target: () -> String?
) : DefaultLifecycleObserver {
    private val resolver = ParentEffectiveContextResolver(activity)
    private val summary = FamilyLocationSummary(activity, network)
    private var job: Job? = null
    init { activity.lifecycle.addObserver(this) }
    override fun onStart(owner: LifecycleOwner) { refresh() }
    override fun onStop(owner: LifecycleOwner) { job?.cancel() }
    fun refresh() {
        job?.cancel()
        view.setText(ru.example.childwatch.designsystem.R.string.cw_distance_loading)
        if (!activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        job = activity.lifecycleScope.launch {
            while (isActive) {
                val id = target()?.trim().orEmpty()
                val own = resolver.resolveOwnParentId()
                val server = resolver.resolveServerUrl()
                val family = resolver.resolveFamilyId()
                val text = try {
                    if (id.isBlank()) activity.getString(ru.example.childwatch.designsystem.R.string.cw_distance_person_missing)
                    else summary.forPerson(id, own)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { activity.getString(ru.example.childwatch.designsystem.R.string.cw_distance_unavailable) }
                if (id == target()?.trim().orEmpty() && own == resolver.resolveOwnParentId() &&
                    server == resolver.resolveServerUrl() && family == resolver.resolveFamilyId()) view.text = text
                delay(30_000L)
            }
        }
    }
}
