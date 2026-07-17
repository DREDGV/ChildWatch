package ru.childwatch.shared.attention.android

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.ScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONObject
import ru.childwatch.shared.attention.AttentionSignalContract
import ru.childwatch.shared.attention.AttentionSignalRequest
import ru.childwatch.shared.attention.AttentionSignalStatus
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

class AttentionSignalSheet(
    private val context: Context,
    private val target: AttentionSignalTarget,
    private val isTransportReady: () -> Boolean,
    private val sendRequest: (JSONObject) -> Boolean,
    private val sendStopRequest: (JSONObject) -> Boolean,
    private val addStatusListener: ((JSONObject) -> Unit) -> Unit,
    private val removeStatusListener: ((JSONObject) -> Unit) -> Unit
) {
    private val dialog = BottomSheetDialog(context)
    private var activeRequest: AttentionSignalRequest? = null
    private lateinit var statusText: TextView
    private lateinit var sendButton: MaterialButton
    private lateinit var stopButton: MaterialButton

    private val statusListener: (JSONObject) -> Unit = listener@{ payload ->
        val event = AttentionSignalJson.statusFromJson(payload) ?: return@listener
        if (event.requestId != activeRequest?.requestId) return@listener
        statusText.post {
            val details = listOfNotNull(event.reason, event.errorCode, event.message)
                .distinct()
                .joinToString(" · ")
            statusText.text = buildString {
                append(statusLabel(event.status))
                if (details.isNotBlank()) append("\n").append(details)
            }
            val canStop = event.status in setOf(
                AttentionSignalStatus.QUEUED,
                AttentionSignalStatus.DELIVERED,
                AttentionSignalStatus.STARTED
            )
            stopButton.visibility = if (canStop) View.VISIBLE else View.GONE
            sendButton.isEnabled = event.status.isTerminal
        }
    }

    fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(24))
        }
        root.addView(text("Сигнал внимания", 24f, Typeface.BOLD))
        root.addView(text("Кому: ${target.targetDisplayName}", 17f, Typeface.BOLD).withTopMargin(10))
        root.addView(
            text("Устройство: ${target.targetDeviceId.shortId()}", 13f, Typeface.NORMAL)
                .withBottomMargin(16)
        )

        val durationSpinner = spinner(
            AttentionSignalContract.selectableDurationsMs.map { "${it / 1_000} сек" }
        ).also { it.setSelection(2) }
        root.addView(label("Длительность"))
        root.addView(durationSpinner)

        val toneValues = AttentionTone.entries.toList()
        val toneSpinner = spinner(listOf("Сигнал", "Мелодия звонка", "Будильник", "Сирена"))
        root.addView(label("Звук").withTopMargin(12))
        root.addView(toneSpinner)

        val volumeText = label("Громкость: 100%").withTopMargin(12) as TextView
        val volumeSeek = SeekBar(context).apply {
            max = 100
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    volumeText.text = "Громкость: $progress%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        root.addView(volumeText)
        root.addView(volumeSeek)

        val vibrationSwitch = SwitchMaterial(context).apply {
            text = "Вибрация"
            isChecked = true
        }
        val patternValues = listOf(
            AttentionVibrationPattern.PULSE,
            AttentionVibrationPattern.URGENT,
            AttentionVibrationPattern.SOS
        )
        val patternSpinner = spinner(listOf("Импульс", "Срочно", "SOS"))
        vibrationSwitch.setOnCheckedChangeListener { _, checked -> patternSpinner.isEnabled = checked }
        root.addView(vibrationSwitch.withTopMargin(8))
        root.addView(label("Ритм вибрации"))
        root.addView(patternSpinner)

        statusText = text("Готов к отправке", 14f, Typeface.BOLD).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(statusText.withTopMargin(16))

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        sendButton = MaterialButton(context).apply {
            text = "Отправить"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
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
                    volumePercent = volumeSeek.progress,
                    vibrate = vibrationSwitch.isChecked,
                    vibrationPattern = if (vibrationSwitch.isChecked) {
                        patternValues[patternSpinner.selectedItemPosition]
                    } else {
                        AttentionVibrationPattern.OFF
                    },
                    createdAt = now,
                    expiresAt = now + AttentionSignalContract.DEFAULT_TTL_MS
                ).normalized(now)
                request.validationError(now)?.let { error ->
                    statusText.text = "Нельзя отправить сигнал: $error"
                    return@setOnClickListener
                }
                activeRequest = request
                statusText.text = "Отправка…"
                sendButton.isEnabled = false
                stopButton.visibility = View.VISIBLE
                if (!sendRequest(AttentionSignalJson.requestToJson(request))) {
                    sendButton.isEnabled = true
                    stopButton.visibility = View.GONE
                    statusText.text = "Сигнал не отправлен: соединение ещё не готово"
                }
            }
        }
        stopButton = MaterialButton(context).apply {
            text = "Остановить"
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
            setOnClickListener {
                val request = activeRequest ?: return@setOnClickListener
                val payload = JSONObject().apply {
                    put("requestId", request.requestId)
                    put("targetDeviceId", request.targetDeviceId)
                    put("requesterDeviceId", request.requesterDeviceId)
                    put("createdAt", System.currentTimeMillis())
                }
                if (sendStopRequest(payload)) {
                    statusText.text = "Отправлена команда остановки…"
                    stopButton.isEnabled = false
                } else {
                    statusText.text = "Не удалось отправить остановку: нет соединения"
                }
            }
        }
        buttons.addView(sendButton)
        buttons.addView(stopButton)
        root.addView(buttons.withTopMargin(8))

        val closeButton = MaterialButton(context).apply {
            text = "Закрыть"
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
        dialog.setOnDismissListener { removeStatusListener(statusListener) }
        dialog.show()
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
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

    private fun spinner(values: List<String>) = Spinner(context).apply {
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

    private fun String.shortId(): String = if (length <= 24) this else take(12) + "…" + takeLast(8)

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
