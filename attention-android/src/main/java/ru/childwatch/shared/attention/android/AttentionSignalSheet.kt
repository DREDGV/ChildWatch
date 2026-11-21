package ru.childwatch.shared.attention.android

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.ScrollView
import android.widget.EditText
import android.text.InputFilter
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONObject
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.childwatch.shared.attention.AttentionSignalContract
import ru.childwatch.shared.attention.AttentionSignalRequest
import ru.childwatch.shared.attention.AttentionSignalStatus
import ru.childwatch.shared.attention.AttentionSignalStatusEvent
import ru.childwatch.shared.attention.AttentionSignalSenderPolicy
import ru.childwatch.shared.attention.AttentionSignalSenderState
import ru.childwatch.shared.attention.AttentionTone
import ru.childwatch.shared.attention.AttentionVibrationPattern

data class AttentionSignalTarget(
    val familyId: String? = null,
    val targetMemberId: String? = null,
    val targetDeviceId: String,
    val targetDisplayName: String,
    val requesterMemberId: String? = null,
    val requesterDeviceId: String,
    val requesterDisplayName: String
)

class AttentionSignalStatusAccessDenied : Exception("Attention signal status access denied")

class AttentionSignalSheet(
    private val context: Context,
    private val target: AttentionSignalTarget,
    private val isTransportReady: () -> Boolean,
    private val sendRequest: (JSONObject) -> Boolean,
    private val sendStopRequest: (JSONObject) -> Boolean,
    private val addStatusListener: ((JSONObject) -> Unit) -> Unit,
    private val removeStatusListener: ((JSONObject) -> Unit) -> Unit,
    private val bindTargetAvatar: ((ImageView) -> Unit)? = null,
    private val isContextCurrent: () -> Boolean = { true },
    private val recoverStatus: (suspend (requestId: String, targetDeviceId: String) -> JSONObject?)? = null,
    private val requestScopeKey: String? = null
) {
    private val dialog = BottomSheetDialog(context)
    private var activeRequest: AttentionSignalRequest? = null
    private lateinit var statusText: TextView
    private lateinit var sendButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var stopWaitingButton: MaterialButton
    private val main = Handler(Looper.getMainLooper())
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recoveryJob: Job? = null
    private var senderState: AttentionSignalSenderState? = null
    private var attemptElapsedAt = 0L
    private var dismissed = false
    private var accessDenied = false
    private var statusDetails = ""
    private var recipientNeedsUpdate = false
    private val lifecycleOwner = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<LifecycleOwner>().firstOrNull()
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss()
    }

    private data class PendingAttempt(val request: AttentionSignalRequest, val state: AttentionSignalSenderState,
        val elapsedAt: Long)
    companion object {
        private val pendingAttempts = linkedMapOf<String, PendingAttempt>()
    }
    private val pendingKey: String? = requestScopeKey?.let {
        org.json.JSONArray(listOf(it, target.familyId, target.requesterMemberId, target.requesterDeviceId,
            target.targetMemberId, target.targetDeviceId)).toString()
    }

    private val statusListener: (JSONObject) -> Unit = listener@{ payload ->
        val event = AttentionSignalJson.statusFromJson(payload) ?: return@listener
        main.post {
            if (!matches(event.requestId, event.targetDeviceId)) return@post
            if (payload.optString("operation") == "STOP") {
                senderState = senderState?.let { AttentionSignalSenderPolicy.markUnknown(it, attemptNow(it)) }
                statusDetails = "Остановка не подтверждена. Сигнал на телефоне мог продолжиться."
                renderSender()
                return@post
            }
            acceptStatus(event)
        }
    }

    private fun contextAllowed(): Boolean {
        if (dismissed || accessDenied || !dialog.isShowing) return false
        if (isContextCurrent()) return true
        accessDenied = true
        recoveryJob?.cancel()
        statusDetails = ""
        statusText.text = "Выбранный профиль или доступ изменился. Закройте окно и выберите получателя заново."
        sendButton.isEnabled = false
        stopButton.visibility = View.GONE
        stopWaitingButton.visibility = View.GONE
        return false
    }

    private fun matches(requestId: String, deviceId: String): Boolean = contextAllowed() &&
        activeRequest?.requestId == requestId && activeRequest?.targetDeviceId == deviceId &&
        target.targetDeviceId == deviceId

    private fun acceptStatus(event: AttentionSignalStatusEvent) {
        val old = senderState ?: return
        val next = AttentionSignalSenderPolicy.onStatus(old, event, attemptNow(old, attemptElapsedAt))
        if (next != old) {
            senderState = next
            recipientNeedsUpdate = event.errorCode == "TARGET_UPDATE_REQUIRED"
            statusDetails = if (recipientNeedsUpdate) {
                "Обновите ChildWatch на телефоне получателя, чтобы отправить текст."
            } else {
                listOfNotNull(event.reason, event.errorCode, event.message).distinct().joinToString(" · ")
            }
        }
        renderSender()
    }

    /** Project monotonic elapsed time onto the attempt's original wall clock. */
    private fun attemptNow(state: AttentionSignalSenderState, elapsedAt: Long = attemptElapsedAt): Long {
        val elapsed = (SystemClock.elapsedRealtime() - elapsedAt).coerceAtLeast(0L)
        return state.sentAt + elapsed.coerceAtMost(Long.MAX_VALUE - state.sentAt)
    }

    private fun rememberAttempt() {
        val key = pendingKey ?: return
        val request = activeRequest ?: return
        val state = senderState ?: return
        val now = attemptNow(state)
        pendingAttempts.entries.removeAll {
            AttentionSignalSenderPolicy.canSend(it.value.state, attemptNow(it.value.state, it.value.elapsedAt))
        }
        if (AttentionSignalSenderPolicy.canSend(state, now)) pendingAttempts.remove(key)
        else pendingAttempts[key] = PendingAttempt(request, state, attemptElapsedAt)
        while (pendingAttempts.size > 32) pendingAttempts.remove(pendingAttempts.keys.first())
    }

    private fun renderSender() {
        if (!contextAllowed()) return
        val state = senderState ?: return
        val now = attemptNow(state)
        val canSend = AttentionSignalSenderPolicy.canSend(state, now)
        val canStop = AttentionSignalSenderPolicy.canStop(state, now)
        statusText.text = buildString {
            when {
                state.isTerminal && recipientNeedsUpdate -> append("Сообщение не отправлено")
                state.isTerminal -> append(statusLabel(requireNotNull(state.status)))
                state.stoppedWaiting -> append("Ожидание прекращено. Это не отменяет сигнал на телефоне.")
                state.isUnknown -> append("Исход сигнала неизвестен. Подтверждения от телефона нет; звук мог быть запущен.")
                state.stopPending -> append("Ждём подтверждения остановки…")
                state.status != null -> append(statusLabel(requireNotNull(state.status)))
                else -> append("Отправка… Ждём подтверждения сервера.")
            }
            if (statusDetails.isNotBlank() && (state.isTerminal || (!state.isUnknown && !state.stoppedWaiting) ||
                    statusDetails == "Остановка не подтверждена. Сигнал на телефоне мог продолжиться.")) {
                append("\n").append(statusDetails)
            }
            if (!state.isTerminal && !canSend && (state.isUnknown || state.stoppedWaiting)) {
                append("\nПовторная отправка доступна через ")
                append(((state.safeUntil - now).coerceAtLeast(0L) + 999L) / 1_000L).append(" сек.")
            } else if (!state.isTerminal && canSend) {
                append("\nБезопасный срок ожидания прошёл. Новый сигнал можно отправить вручную.")
            }
        }
        sendButton.isEnabled = canSend
        stopButton.visibility = if (canStop) View.VISIBLE else View.GONE
        stopButton.isEnabled = canStop && !state.stopPending
        stopWaitingButton.visibility = if (!state.isTerminal && !state.stoppedWaiting && !canSend) View.VISIBLE else View.GONE
        (sendButton.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.weight = if (canStop) 1f else 2f
            sendButton.layoutParams = params
        }
        rememberAttempt()
    }

    private val waitTick = object : Runnable {
        override fun run() {
            if (!contextAllowed()) return
            val state = senderState
            if (state != null) {
                val now = attemptNow(state)
                senderState = AttentionSignalSenderPolicy.tick(state, now)
                renderSender()
                if (recoverStatus != null && recoveryJob?.isActive != true &&
                    AttentionSignalSenderPolicy.shouldPoll(senderState!!, now)) recoverCurrentStatus()
                if (senderState!!.isTerminal || AttentionSignalSenderPolicy.canSend(senderState!!, now)) {
                    recoveryJob?.cancel()
                    return
                }
            }
            main.postDelayed(this, 1_000L)
        }
    }

    private fun recoverCurrentStatus() {
        val callback = recoverStatus ?: return
        val request = activeRequest ?: return
        val state = senderState ?: return
        senderState = AttentionSignalSenderPolicy.markPolled(state, attemptNow(state))
        recoveryJob = recoveryScope.launch {
            try {
                val result = callback(request.requestId, request.targetDeviceId)
                main.post {
                    if (!matches(request.requestId, request.targetDeviceId)) return@post
                    if (senderState?.stoppedWaiting == true) return@post
                    if (result == null) return@post
                    // A read-only response cannot switch this dialog's actor or recipient.
                    if (result.opt("success") != true || result.optString("requestId") != request.requestId ||
                        result.optString("targetDeviceId") != request.targetDeviceId ||
                        result.optString("familyId") != target.familyId ||
                        result.optString("actorMemberId") != target.requesterMemberId) return@post
                    when (result.optString("outcome")) {
                        "KNOWN" -> result.optJSONObject("status")?.let(AttentionSignalJson::statusFromJson)?.let {
                            if (matches(it.requestId, it.targetDeviceId)) acceptStatus(it)
                        }
                        "UNKNOWN" -> {
                            senderState = senderState?.let { AttentionSignalSenderPolicy.markUnknown(it, attemptNow(it)) }
                            renderSender()
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (denied: AttentionSignalStatusAccessDenied) {
                main.post {
                    if (!matches(request.requestId, request.targetDeviceId)) return@post
                    accessDenied = true
                    statusDetails = ""
                    pendingKey?.let(pendingAttempts::remove)
                    statusText.text = "Нет доступа к статусу этого сигнала. Закройте окно и проверьте права в семье."
                    sendButton.isEnabled = false
                    stopButton.visibility = View.GONE
                    stopWaitingButton.visibility = View.GONE
                    main.removeCallbacks(waitTick)
                }
            } catch (_: Exception) {
                // No transport evidence is a local unknown, never a terminal delivery outcome.
            }
        }
    }

    private fun cleanup() {
        if (dismissed) return
        rememberAttempt()
        dismissed = true
        main.removeCallbacks(waitTick)
        recoveryScope.cancel()
        removeStatusListener(statusListener)
        lifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
    }

    fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            requestFocus()
            setPadding(dp(24), dp(16), dp(24), dp(24))
        }
        root.addView(text("Сигнал внимания", 24f, Typeface.BOLD))
        root.addView(text("Звук и вибрация помогут привлечь внимание к телефону.", 14f, Typeface.NORMAL)
            .withTopMargin(4))
        root.addView(targetPersonCard().withTopMargin(16).withBottomMargin(16))

        val preferences = context.getSharedPreferences("attention_sender_settings", Context.MODE_PRIVATE)
        val durations = AttentionSignalContract.selectableDurationsMs
        val defaultDuration = 2 // Five seconds, the existing default.
        val settings = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val durationSpinner = spinner(
            AttentionSignalContract.selectableDurationsMs.map { "${it / 1_000} сек" }
        ).apply {
            setSelection(durations.indexOf(preferences.getLong("duration", durations[defaultDuration]))
                .takeIf { it >= 0 } ?: defaultDuration)
            contentDescription = "Длительность"
        }
        settings.addView(label("Длительность"))
        settings.addView(durationSpinner)

        val toneValues = AttentionTone.entries.toList()
        val toneSpinner = spinner(listOf("Сигнал", "Мелодия звонка", "Будильник", "Сирена"))
        toneSpinner.setSelection(toneValues.indexOf(AttentionTone.fromWire(preferences.getString("tone", null))))
        toneSpinner.contentDescription = "Звук"
        settings.addView(label("Звук").withTopMargin(12))
        settings.addView(toneSpinner)

        val volumeText = label("Громкость: 100%").withTopMargin(12) as TextView
        val volumeSeek = SeekBar(context).apply {
            max = 100
            progress = preferences.getInt("volume", 100).coerceIn(0, max)
            contentDescription = "Громкость"
        }
        volumeText.text = "Громкость: ${volumeSeek.progress}%"
        settings.addView(volumeText)
        settings.addView(volumeSeek)

        val vibrationSwitch = SwitchMaterial(context).apply {
            text = "Вибрация"
            isChecked = preferences.getBoolean("vibrate", true)
        }
        val patternValues = listOf(
            AttentionVibrationPattern.PULSE,
            AttentionVibrationPattern.URGENT,
            AttentionVibrationPattern.SOS
        )
        val patternSpinner = spinner(listOf("Импульс", "Срочно", "SOS"))
        settings.addView(vibrationSwitch.withTopMargin(8))
        val patternLabel = label("Ритм вибрации")
        patternSpinner.contentDescription = "Ритм вибрации"
        patternSpinner.setSelection(patternValues.indexOf(
            AttentionVibrationPattern.fromWire(preferences.getString("pattern", null))
        ).coerceAtLeast(0))
        settings.addView(patternLabel)
        settings.addView(patternSpinner)

        val settingsSummary = text("", 14f, Typeface.NORMAL)
        val vibrationOnly = SwitchMaterial(context).apply {
            text = "Только вибрация"
            isChecked = preferences.getBoolean("vibration_only", false)
            minHeight = dp(48)
        }
        fun updateSettingsSummary() {
            val patternVisibility = if (vibrationSwitch.isChecked || vibrationOnly.isChecked) View.VISIBLE else View.GONE
            patternLabel.visibility = patternVisibility
            patternSpinner.visibility = patternVisibility
            settingsSummary.text = buildString {
                append(if (vibrationOnly.isChecked) "Вибрация" else toneSpinner.selectedItem).append(", ")
                append(durations[durationSpinner.selectedItemPosition] / 1_000).append(" сек")
                if (!vibrationOnly.isChecked) {
                    append(", ").append(volumeSeek.progress).append("%")
                    append(if (vibrationSwitch.isChecked) ", с вибрацией" else ", без вибрации")
                }
            }
        }
        val settingsToggle = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Настроить сигнал"
            isAllCaps = false
            minHeight = dp(48)
            setOnClickListener {
                val expanded = settings.visibility != View.VISIBLE
                settings.visibility = if (expanded) View.VISIBLE else View.GONE
                text = if (expanded) "Свернуть настройки" else "Настроить сигнал"
            }
        }
        settings.visibility = View.GONE
        val selectionListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateSettingsSummary()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        durationSpinner.onItemSelectedListener = selectionListener
        toneSpinner.onItemSelectedListener = selectionListener
        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                volumeText.text = "Громкость: $progress%"
                updateSettingsSummary()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        vibrationSwitch.setOnCheckedChangeListener { _, checked ->
            patternSpinner.isEnabled = checked
            updateSettingsSummary()
        }
        val savedText = text("", 14f, Typeface.NORMAL).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        settings.addView(MaterialButton(context).apply {
            text = "Запомнить на этом телефоне"
            isAllCaps = false
            setOnClickListener {
                if (!contextAllowed()) return@setOnClickListener
                preferences.edit()
                    .putLong("duration", durations[durationSpinner.selectedItemPosition])
                    .putString("tone", toneValues[toneSpinner.selectedItemPosition].name)
                    .putBoolean("vibration_only", vibrationOnly.isChecked)
                    .putInt("volume", volumeSeek.progress)
                    .putBoolean("vibrate", vibrationSwitch.isChecked)
                    .putString("pattern", patternValues[patternSpinner.selectedItemPosition].name)
                    .apply()
                settingsToggle.performClick()
                savedText.text = "Настройки сигнала сохранены"
                savedText.visibility = View.VISIBLE
            }
        }.withTopMargin(8))
        settings.addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Вернуть стандартные значения"
            isAllCaps = false
            setOnClickListener {
                durationSpinner.setSelection(defaultDuration)
                toneSpinner.setSelection(0)
                vibrationOnly.isChecked = false
                volumeSeek.progress = 100
                vibrationSwitch.isChecked = true
                patternSpinner.setSelection(0)
                updateSettingsSummary()
            }
        })
        updateSettingsSummary()
        fun updateOutputMode() {
            val only = vibrationOnly.isChecked
            toneSpinner.isEnabled = !only
            volumeSeek.isEnabled = !only
            vibrationSwitch.isEnabled = !only
            updateSettingsSummary()
        }
        vibrationOnly.setOnCheckedChangeListener { _, _ -> updateOutputMode() }
        updateOutputMode()
        root.addView(vibrationOnly)
        root.addView(settingsSummary)
        root.addView(savedText)
        root.addView(settingsToggle)
        root.addView(MaterialCardView(context).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            addView(settings)
        })

        val noteInput = EditText(context).apply {
            hint = "Сообщение к сигналу"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            filters = arrayOf(InputFilter.LengthFilter(AttentionSignalContract.MAX_MESSAGE_LENGTH))
        }
        val noteDuration = spinner(listOf("До нажатия «Прочитано»", "15 секунд", "30 секунд", "1 минута"))
        noteDuration.contentDescription = "Когда закрыть сообщение"
        noteDuration.visibility = View.GONE
        noteInput.doAfterTextChanged {
            noteDuration.visibility = if (it.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        root.addView(noteInput.withTopMargin(8))
        root.addView(noteDuration)

        statusText = text("Готов к отправке", 14f, Typeface.BOLD).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(MaterialCardView(context).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            addView(statusText)
        }.withTopMargin(16))

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        sendButton = MaterialButton(context).apply {
            text = "Отправить сигнал"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                if (!contextAllowed()) return@setOnClickListener
                senderState?.let {
                    if (!AttentionSignalSenderPolicy.canSend(it, attemptNow(it))) return@setOnClickListener
                }
                if (!isTransportReady()) {
                    statusText.text = "Нет защищённого соединения. Подождите восстановления связи."
                    return@setOnClickListener
                }
                val now = System.currentTimeMillis()
                val request = AttentionSignalRequest(
                    familyId = target.familyId,
                    targetMemberId = target.targetMemberId,
                    targetDeviceId = target.targetDeviceId,
                    requesterMemberId = target.requesterMemberId,
                    requesterDeviceId = target.requesterDeviceId,
                    requesterDisplayName = target.requesterDisplayName,
                    tone = toneValues[toneSpinner.selectedItemPosition],
                    durationMs = AttentionSignalContract.selectableDurationsMs[durationSpinner.selectedItemPosition],
                    volumePercent = if (vibrationOnly.isChecked) 0 else volumeSeek.progress,
                    vibrate = vibrationOnly.isChecked || vibrationSwitch.isChecked,
                    vibrationPattern = if (vibrationOnly.isChecked || vibrationSwitch.isChecked) {
                        patternValues[patternSpinner.selectedItemPosition]
                    } else {
                        AttentionVibrationPattern.OFF
                    },
                    createdAt = now,
                    expiresAt = now + AttentionSignalContract.DEFAULT_TTL_MS,
                    note = noteInput.text.toString().trim().takeIf { it.isNotEmpty() },
                    noteDurationSeconds = if (noteInput.text.isNullOrBlank()) 0
                        else AttentionSignalContract.messageDisplayDurationsSeconds[noteDuration.selectedItemPosition]
                ).normalized(now)
                request.validationError(now)?.let { error ->
                    statusText.text = "Нельзя отправить сигнал: $error"
                    return@setOnClickListener
                }
                activeRequest = request
                recipientNeedsUpdate = false
                attemptElapsedAt = SystemClock.elapsedRealtime()
                senderState = AttentionSignalSenderPolicy.start(request.requestId, request.targetDeviceId, now, request.durationMs)
                statusDetails = ""
                renderSender()
                main.removeCallbacks(waitTick)
                main.postDelayed(waitTick, 1_000L)
                if (!sendRequest(AttentionSignalJson.requestToJson(request))) {
                    // A false local emit is not proof that an earlier network write never arrived.
                    senderState = AttentionSignalSenderPolicy.markUnknown(senderState!!, attemptNow(senderState!!))
                    renderSender()
                }
            }
        }
        stopButton = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Остановить"
            isAllCaps = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
            setOnClickListener {
                if (!contextAllowed()) return@setOnClickListener
                val request = activeRequest ?: return@setOnClickListener
                val state = senderState ?: return@setOnClickListener
                if (!AttentionSignalSenderPolicy.canStop(state, attemptNow(state))) return@setOnClickListener
                val payload = JSONObject().apply {
                    put("requestId", request.requestId)
                    put("targetDeviceId", request.targetDeviceId)
                    put("requesterDeviceId", request.requesterDeviceId)
                    put("createdAt", System.currentTimeMillis())
                }
                if (sendStopRequest(payload)) {
                    senderState = AttentionSignalSenderPolicy.requestStop(state, attemptNow(state))
                    renderSender()
                } else {
                    statusText.text = "Не удалось отправить остановку: нет соединения"
                }
            }
        }
        buttons.addView(sendButton)
        buttons.addView(stopButton)
        root.addView(buttons.withTopMargin(8))

        stopWaitingButton = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Прекратить ожидание"
            isAllCaps = false
            minHeight = dp(48)
            visibility = View.GONE
            setOnClickListener {
                if (!contextAllowed()) return@setOnClickListener
                senderState = senderState?.let(AttentionSignalSenderPolicy::stopWaiting)
                recoveryJob?.cancel()
                renderSender()
            }
        }
        root.addView(stopWaitingButton.withTopMargin(6))

        val closeButton = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Закрыть"
            isAllCaps = false
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(closeButton.withTopMargin(6))

        addStatusListener(statusListener)
        dialog.setContentView(
            ScrollView(context).apply {
                isFillViewport = true
                addView(root)
            }
        )
        dialog.setOnDismissListener { cleanup() }
        lifecycleOwner?.lifecycle?.addObserver(lifecycleObserver)
        dialog.show()
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        pendingKey?.let { key -> pendingAttempts[key] }?.let { pending ->
            if (!AttentionSignalSenderPolicy.canSend(pending.state, attemptNow(pending.state, pending.elapsedAt))) {
                activeRequest = pending.request
                attemptElapsedAt = pending.elapsedAt
                // Reopening resumes read-only verification; it never emits another sound.
                senderState = pending.state.copy(stoppedWaiting = false)
                renderSender()
            }
        }
        main.post(waitTick)
    }

    private fun statusLabel(status: AttentionSignalStatus): String = when (status) {
        AttentionSignalStatus.QUEUED -> "Сигнал принят сервером"
        AttentionSignalStatus.DELIVERED -> "Доставлен на устройство"
        AttentionSignalStatus.STARTED -> "Сигнал воспроизводится"
        AttentionSignalStatus.COMPLETED -> "Сигнал завершён"
        AttentionSignalStatus.STOPPED -> "Сигнал остановлен"
        AttentionSignalStatus.REJECTED -> "Сигнал отклонён"
        AttentionSignalStatus.FAILED -> "Ошибка воспроизведения"
        AttentionSignalStatus.EXPIRED -> "Время ожидания истекло"
    }

    private fun targetPersonCard(): MaterialCardView {
        val avatar = ShapeableImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            scaleType = ImageView.ScaleType.CENTER_CROP
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, dp(24).toFloat())
                .build()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        bindTargetAvatar?.invoke(avatar)

        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
            }
            addView(text(target.targetDisplayName, 18f, Typeface.BOLD))
            addView(
                text("Сигнал прозвучит на телефоне этого человека", 13f, Typeface.NORMAL)
                    .withTopMargin(3)
            )
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(avatar)
            addView(details)
        }
        return MaterialCardView(context).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            addView(row)
        }
    }

    private fun spinner(values: List<String>) = Spinner(context).apply {
        minimumHeight = dp(48)
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, values)
    }

    private fun label(value: String): TextView = text(value, 14f, Typeface.BOLD)

    private fun text(value: String, size: Float, style: Int): TextView = TextView(context).apply {
        text = value
        textSize = size
        setTypeface(typeface, style)
    }

    private fun <T : View> T.withTopMargin(value: Int): T = apply {
        layoutParams = (layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            .apply { topMargin = dp(value) }
    }

    private fun <T : View> T.withBottomMargin(value: Int): T = apply {
        layoutParams = (layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            .apply { bottomMargin = dp(value) }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
