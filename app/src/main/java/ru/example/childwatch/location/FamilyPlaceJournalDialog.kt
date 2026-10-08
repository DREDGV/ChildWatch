package ru.example.childwatch.location

import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONObject
import ru.childwatch.shared.family.FamilyPlaceJournalEvent
import ru.childwatch.shared.family.FamilyPlaceJournalPage
import ru.childwatch.shared.family.FamilyPlaceJournalPolicy
import ru.example.childwatch.R
import ru.example.childwatch.network.NetworkClient
import java.text.DateFormat
import java.util.Date

/** Read-only snapshot pagination; opening this dialog never changes notification delivery cursors. */
class FamilyPlaceJournalDialog(
    private val activity: ComponentActivity,
    private val network: NetworkClient,
    private val target: FamilyPlacesController.Target,
    private val scope: String,
    private val current: () -> Boolean
) {
    private var loadJob: Job? = null
    private var scopeGuardJob: Job? = null
    private var closed = false
    private var snapshot: Long? = null
    private var nextBefore: Long? = null
    private var hasMore = false
    private var loadedPages = 0
    private val displayedIds = mutableSetOf<Long>()
    private lateinit var dialog: AlertDialog
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private lateinit var scrolling: ScrollView
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY && ::dialog.isInitialized) dialog.dismiss()
        else if (event == Lifecycle.Event.ON_RESUME && ::dialog.isInitialized && !current()) dialog.dismiss()
    }

    fun show() {
        if (!current() || activity.isFinishing || activity.isDestroyed) return
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        content.addView(text(target.name, 18f).apply { setTypeface(typeface, Typeface.BOLD) })
        content.addView(text(activity.getString(R.string.family_places_journal_hint), 13f).apply {
            setTextColor(activity.getColor(R.color.cw_color_on_surface_variant))
            setPadding(0, dp(8), 0, dp(8))
        })
        status = text("", 14f).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(status)
        rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(rows)
        scrolling = ScrollView(activity).apply { addView(content) }
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.family_places_journal_title)
            .setView(scrolling)
            .setPositiveButton(R.string.family_places_journal_more, null)
            .setNeutralButton(R.string.family_places_retry, null)
            .setNegativeButton(R.string.family_places_journal_close, null)
            .create()
        dialog.setOnDismissListener {
            closed = true
            loadJob?.cancel()
            scopeGuardJob?.cancel()
            displayedIds.clear()
            rows.removeAllViews()
            activity.lifecycle.removeObserver(lifecycleObserver)
        }
        activity.lifecycle.addObserver(lifecycleObserver)
        dialog.setOnShowListener {
            scrolling.layoutParams.height = minOf(dp(460), (activity.resources.displayMetrics.heightPixels * .62f).toInt())
            scrolling.requestLayout()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { loadPage() }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { loadPage() }
            loadPage()
            scopeGuardJob = activity.lifecycleScope.launch {
                while (!closed && dialog.isShowing) {
                    delay(1_000L)
                    if (!current()) { dialog.dismiss(); break }
                }
            }
        }
        dialog.show()
    }

    private fun active(): Boolean = !closed && dialog.isShowing && current() &&
        !activity.isFinishing && !activity.isDestroyed

    private fun loadPage() {
        if (!active()) { if (dialog.isShowing) dialog.dismiss(); return }
        if (loadJob?.isActive == true) return
        if (loadedPages >= 6) return
        val requestedBefore = nextBefore
        val requestedSnapshot = snapshot
        status.setText(R.string.family_places_journal_loading)
        status.visibility = View.VISIBLE
        buttons(loading = true, retry = false)
        loadJob = activity.lifecycleScope.launch {
            try {
                val response = network.familyPlacesRequest(
                    target.familyId, suffix = "/history", targetMemberId = target.memberId,
                    expectedScope = scope, before = requestedBefore, snapshot = requestedSnapshot
                )
                currentCoroutineContext().ensureActive()
                if (!active()) { dialog.dismiss(); return@launch }
                val owner = org.json.JSONArray(scope).getString(2)
                val page = FamilyPlaceJournalPolicy.validatePage(parse(response), target.familyId, owner,
                    target.memberId, requestedBefore, requestedSnapshot, System.currentTimeMillis())
                // Never silently deduplicate a malformed continuation page.
                require(page.events.none { it.id in displayedIds }) { "PLACE_HISTORY_DUPLICATE" }
                page.events.forEach { event ->
                    displayedIds += event.id
                    rows.addView(eventRow(event))
                }
                snapshot = page.snapshot
                nextBefore = page.nextBefore
                loadedPages++
                hasMore = page.hasMore && loadedPages < 6
                if (page.hasMore && loadedPages >= 6) {
                    status.text = activity.getString(R.string.family_places_journal_limit, displayedIds.size)
                } else status.setText(if (displayedIds.isEmpty()) R.string.family_places_journal_empty
                    else if (!hasMore) R.string.family_places_journal_end else R.string.family_places_journal_older_available)
                buttons(loading = false, retry = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!active()) { dialog.dismiss(); return@launch }
                val denied = failure.message == "PLACE_PERMISSION_DENIED"
                if (denied) {
                    // Revocation is not a network retry and must not leave previously read rows visible.
                    rows.removeAllViews()
                    displayedIds.clear()
                    hasMore = false
                }
                status.setText(when {
                    denied -> R.string.family_places_permission
                    failure is IllegalArgumentException || failure is org.json.JSONException -> R.string.family_places_journal_invalid
                    else -> R.string.family_places_journal_error
                })
                buttons(loading = false, retry = !denied)
            }
        }
    }

    private fun buttons(loading: Boolean, retry: Boolean) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            visibility = if (!loading && !retry && hasMore) View.VISIBLE else View.GONE
            isEnabled = !loading
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
            visibility = if (!loading && retry) View.VISIBLE else View.GONE
            isEnabled = !loading
        }
    }

    private fun eventRow(event: FamilyPlaceJournalEvent): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        addView(text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(event.measuredAt)), 13f).apply {
                setTextColor(activity.getColor(R.color.cw_color_on_surface_variant))
            })
        addView(text(activity.getString(if (event.transition == "ENTER") R.string.family_places_journal_enter
            else R.string.family_places_journal_exit, event.placeName), 16f).apply {
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, 0)
            })
        addView(View(activity).apply { setBackgroundColor(activity.getColor(R.color.cw_color_outline_variant)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(12) })
    }

    private fun parse(response: JSONObject): FamilyPlaceJournalPage {
        require(response.get("success") == true)
        val eventRows = response.getJSONArray("events")
        val events = (0 until eventRows.length()).map { index ->
            val row = eventRows.getJSONObject(index)
            FamilyPlaceJournalEvent(integer(row, "id"), string(row, "family_id"), string(row, "owner_member_id"),
                string(row, "target_member_id"), integer(row, "measured_at"), string(row, "transition"), string(row, "place_name"))
        }
        val hasMore = response.get("hasMore") as? Boolean ?: throw IllegalArgumentException("PLACE_HISTORY_BOOLEAN_INVALID")
        require(response.has("nextBefore"))
        val next = if (response.isNull("nextBefore")) null else integer(response, "nextBefore")
        val retention = integer(response, "retentionDays")
        require(retention in 0..Int.MAX_VALUE.toLong())
        return FamilyPlaceJournalPage(string(response, "familyId"), string(response, "ownerMemberId"),
            string(response, "targetMemberId"), events, integer(response, "snapshot"), next, hasMore, retention.toInt())
    }
    private fun integer(row: JSONObject, key: String): Long = when (val value = row.get(key)) {
        is Long -> value
        is Int -> value.toLong()
        else -> throw IllegalArgumentException("PLACE_HISTORY_INTEGER_INVALID")
    }
    private fun string(row: JSONObject, key: String): String =
        row.get(key) as? String ?: throw IllegalArgumentException("PLACE_HISTORY_STRING_INVALID")
    private fun text(value: String, size: Float) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(activity.getColor(R.color.cw_color_on_surface))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
