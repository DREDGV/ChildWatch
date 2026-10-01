package ru.example.childwatch.location

import android.content.Intent
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.example.childwatch.R
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.profile.ParentEffectiveContextResolver

/** Server places belong to an adult and a canonical person, never to this phone's GPS. */
class FamilyPlacesController(
    private val activity: ComponentActivity,
    private val network: NetworkClient,
    private val target: () -> Target?,
    private val center: () -> Pair<Double, Double>,
    private val rendered: (List<JSONObject>) -> Unit
) {
    data class Target(val familyId: String, val memberId: String, val name: String)
    private val dialogs = mutableSetOf<AlertDialog>()
    init {
        activity.lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
                dialogs.toList().forEach { it.dismiss() }; dialogs.clear(); fetchJob?.cancel()
            }
        })
    }
    private fun tracked(dialog: AlertDialog): AlertDialog {
        dialogs += dialog; dialog.setOnDismissListener { dialogs.remove(dialog) }; return dialog
    }
    private fun fit(scroll: ScrollView) {
        scroll.layoutParams.height = minOf(dp(460), (activity.resources.displayMetrics.heightPixels * .62f).toInt())
        scroll.requestLayout()
    }
    private var fetchJob: Job? = null
    private var lastTarget: Target? = null
    private var lastFetch = 0L
    private var places: List<JSONObject> = emptyList()
    private val resolver = ParentEffectiveContextResolver(activity)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun body() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(12))
    }
    private fun label(text: String) = TextView(activity).apply {
        this.text = text; textSize = 14f; setPadding(0, dp(8), 0, dp(8))
    }
    private fun scroll(content: LinearLayout) = ScrollView(activity).apply { addView(content) }
    private fun current(t: Target, server: String, own: String): Boolean =
        target()?.let { it.familyId == t.familyId && it.memberId == t.memberId } == true &&
        resolver.resolveServerUrl() == server && resolver.resolveOwnParentId() == own && !activity.isDestroyed && !activity.isFinishing
    private fun error(error: Exception): String = activity.getString(
        if (error.message == "PLACE_PERMISSION_DENIED") R.string.family_places_permission else R.string.family_places_error)

    fun refresh(force: Boolean = false, failed: ((String) -> Unit)? = null, ready: (() -> Unit)? = null) {
        val t = target()
        if (t == null) { places = emptyList(); rendered(places); failed?.invoke(activity.getString(R.string.family_places_choose)); return }
        if (t != lastTarget) {
            fetchJob?.cancel(); places = emptyList(); rendered(places); lastFetch = 0; lastTarget = t
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastFetch < 30_000L) return
        fetchJob?.cancel(); lastFetch = now
        val server = resolver.resolveServerUrl(); val own = resolver.resolveOwnParentId()
        fetchJob = activity.lifecycleScope.launch {
            try {
                val result = network.familyPlacesRequest(t.familyId, targetMemberId = t.memberId).getJSONArray("places")
                if (!current(t, server, own)) { failed?.invoke(activity.getString(R.string.family_places_choose)); return@launch }
                places = (0 until result.length()).map { result.getJSONObject(it) }
                rendered(places); ready?.invoke()
            } catch (cancelled: CancellationException) {
                if (!activity.isDestroyed) failed?.invoke(activity.getString(R.string.family_places_choose))
                throw cancelled
            }
            catch (failure: Exception) {
                if (current(t, server, own)) {
                    places = emptyList(); rendered(places)
                    failed?.invoke(error(failure))
                }
            }
        }
    }
    fun show() {
        val t = target() ?: run { Toast.makeText(activity, R.string.family_places_choose, Toast.LENGTH_SHORT).show(); return }
        val loading = tracked(MaterialAlertDialogBuilder(activity).setTitle(R.string.family_places_title)
            .setMessage(R.string.family_places_loading).setPositiveButton(R.string.family_places_retry, null).setNegativeButton(R.string.family_places_cancel, null).show())
        loading.getButton(AlertDialog.BUTTON_POSITIVE).visibility = android.view.View.GONE
        refresh(force = true, failed = { message ->
            if (loading.isShowing) {
                loading.setMessage(message)
                loading.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                    visibility = android.view.View.VISIBLE
                    setOnClickListener { loading.dismiss(); show() }
                }
            }
        }) {
            if (loading.isShowing) { loading.dismiss(); showList(t) }
        }
    }
    private fun showList(t: Target) {
        val content = body()
        content.addView(label(activity.getString(R.string.family_places_member, t.name)))
        content.addView(label(activity.getString(R.string.family_places_notification_hint)))
        val permissions = MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            setText(R.string.family_places_notification_settings); setOnClickListener {
                activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
            }
        }
        content.addView(permissions)
        if (places.isEmpty()) content.addView(label(activity.getString(R.string.family_places_empty)))
        val scrolling = scroll(content)
        val dialog = tracked(MaterialAlertDialogBuilder(activity).setTitle(R.string.family_places_title).setView(scrolling)
            .setPositiveButton(R.string.family_places_add, null).setNegativeButton(R.string.family_places_cancel, null).create())
        places.forEach { place ->
            content.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                isAllCaps = false; setSingleLine(false)
                text = "${place.getString("name")} · ${place.getInt("radius")} м\n" +
                    activity.getString(if (place.getInt("enabled") == 1) R.string.family_places_active else R.string.family_places_paused)
                setOnClickListener { dialog.dismiss(); actions(t, place) }
            })
        }
        dialog.setOnShowListener { fit(scrolling); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { dialog.dismiss(); edit(t, null) } }
        dialog.show()
    }
    private fun actions(t: Target, place: JSONObject) {
        val active = place.getInt("enabled") == 1
        MaterialAlertDialogBuilder(activity).setTitle(place.getString("name"))
            .setItems(arrayOf(activity.getString(R.string.family_places_edit),
                activity.getString(if (active) R.string.family_places_pause else R.string.family_places_resume),
                activity.getString(R.string.family_places_delete))) { _, which ->
                when (which) {
                    0 -> edit(t, place)
                    1 -> mutate(t, "PATCH", place.getString("id"), JSONObject().put("enabled", !active))
                    2 -> MaterialAlertDialogBuilder(activity).setTitle(R.string.family_places_delete)
                        .setMessage(activity.getString(R.string.family_places_delete_confirm, place.getString("name")))
                        .setNegativeButton(R.string.family_places_cancel, null)
                        .setPositiveButton(R.string.family_places_delete) { _, _ -> mutate(t, "DELETE", place.getString("id"), null) }.show()
                }
            }.show()
    }
    private fun mutate(t: Target, method: String, id: String, data: JSONObject?) {
        val server = resolver.resolveServerUrl(); val own = resolver.resolveOwnParentId()
        if (!current(t, server, own)) return
        activity.lifecycleScope.launch {
            try {
                network.familyPlacesRequest(t.familyId, method, "/$id", data)
                if (current(t, server, own)) show()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Toast.makeText(activity, error(failure), Toast.LENGTH_LONG).show() }
        }
    }
    private fun edit(t: Target, existing: JSONObject?) {
        val content = body()
        val requestId = java.util.UUID.randomUUID().toString()
        content.addView(label(activity.getString(R.string.family_places_member, t.name)))
        val field = TextInputLayout(activity).apply { hint = activity.getString(R.string.family_places_name); boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE }
        val name = TextInputEditText(activity).apply { setSingleLine(true); setText(existing?.getString("name") ?: "") }
        field.addView(name); content.addView(field)
        var point = existing?.let { it.getDouble("latitude") to it.getDouble("longitude") } ?: center()
        val pointLabel = label(activity.getString(R.string.family_places_center, point.first, point.second))
        content.addView(pointLabel)
        if (existing != null) content.addView(MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            setText(R.string.family_places_move_center); setOnClickListener {
                point = center(); pointLabel.text = activity.getString(R.string.family_places_center, point.first, point.second)
            }
        })
        val radii = intArrayOf(100, 200, 300, 500, 1000, 2000)
        val radiusLabel = label(""); content.addView(radiusLabel)
        val radius = SeekBar(activity).apply { max = radii.lastIndex; progress = radii.indexOf(existing?.optInt("radius", 200) ?: 200).coerceAtLeast(0) }
        fun radiusText() { radiusLabel.text = activity.getString(R.string.family_places_radius, radii[radius.progress]) }
        radius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, value: Int, fromUser: Boolean) { radiusText() }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        }); radiusText(); content.addView(radius)
        val enter = MaterialSwitch(activity).apply { setText(R.string.family_places_enter); isChecked = existing?.optInt("on_enter", 1) != 0 }
        val exit = MaterialSwitch(activity).apply { setText(R.string.family_places_exit); isChecked = existing?.optInt("on_exit", 1) != 0 }
        content.addView(enter); content.addView(exit); content.addView(label(activity.getString(R.string.family_places_hint)))
        val errorLabel = label(""); content.addView(errorLabel)
        val scrolling = scroll(content)
        val dialog = tracked(MaterialAlertDialogBuilder(activity).setTitle(if (existing == null) R.string.family_places_add else R.string.family_places_edit)
            .setView(scrolling).setPositiveButton(R.string.family_places_save, null).setNegativeButton(R.string.family_places_cancel, null).create())
        dialog.setOnShowListener {
            fit(scrolling)
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                val value = name.text.toString().trim()
                if (value.length !in 2..80 || (!enter.isChecked && !exit.isChecked)) { field.error = activity.getString(R.string.family_places_validation); return@setOnClickListener }
                field.error = null; errorLabel.text = ""
                val server = resolver.resolveServerUrl(); val own = resolver.resolveOwnParentId()
                if (!current(t, server, own)) { dialog.dismiss(); return@setOnClickListener }
                val data = JSONObject().put("targetMemberId", t.memberId).put("name", value).put("requestId", requestId)
                    .put("latitude", point.first).put("longitude", point.second).put("radius", radii[radius.progress])
                    .put("onEnter", enter.isChecked).put("onExit", exit.isChecked)
                button.setText(R.string.family_places_saving); button.isEnabled = false; dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false; dialog.setCancelable(false)
                activity.lifecycleScope.launch {
                    try {
                        network.familyPlacesRequest(t.familyId, if (existing == null) "POST" else "PATCH", existing?.let { "/" + it.getString("id") } ?: "", data)
                        if (current(t, server, own)) { dialog.dismiss(); show(); FamilyPlaceSync.sync(activity, force = true) }
                        else dialog.dismiss()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { errorLabel.text = error(failure) }
                    finally { if (dialog.isShowing) { button.setText(R.string.family_places_save); button.isEnabled = true; dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true; dialog.setCancelable(true) } }
                }
            }
        }
        dialog.show()
    }
}
