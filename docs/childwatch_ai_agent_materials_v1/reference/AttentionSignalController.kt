package ru.childwatch.shared.attention

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Референс. Адаптируй package/module, notification и DI к проекту.
 */
class AttentionSignalController(
    context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun onStarted(request: AttentionSignalRequest)
        fun onStopped(request: AttentionSignalRequest, reason: String)
        fun onCompleted(request: AttentionSignalRequest)
        fun onFailed(
            request: AttentionSignalRequest,
            code: String,
            error: Throwable?
        )
    }

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val stopping = AtomicBoolean(false)

    private var active: AttentionSignalRequest? = null
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var previousAlarmVolume: Int? = null
    private var timeout: Runnable? = null

    @Synchronized
    fun start(raw: AttentionSignalRequest) {
        val request = raw.normalized()
        request.validationError()?.let {
            callback.onFailed(request, it, null)
            return
        }

        active?.let { stopInternal("REPLACED", true) }
        stopping.set(false)
        active = request

        try {
            acquireWakeLock(request.durationMs)
            setTemporaryAlarmVolume(request.volumePercent)
            startVibration(request)
            startSound(request)

            timeout = Runnable {
                synchronized(this) {
                    if (active?.requestId == request.requestId) {
                        stopInternal("COMPLETED", false)
                        callback.onCompleted(request)
                    }
                }
            }.also {
                handler.postDelayed(it, request.durationMs)
            }

            callback.onStarted(request)
        } catch (error: Throwable) {
            cleanup()
            active = null
            callback.onFailed(
                request,
                "PLAYBACK_FAILED",
                error
            )
        }
    }

    @Synchronized
    fun stop(
        requestId: String? = null,
        reason: String = "LOCAL_USER"
    ): Boolean {
        val current = active ?: return false
        if (!requestId.isNullOrBlank() &&
            current.requestId != requestId
        ) return false

        stopInternal(reason, true)
        return true
    }

    @Synchronized
    fun isPlaying(): Boolean = active != null

    private fun startSound(request: AttentionSignalRequest) {
        if (request.volumePercent == 0) return

        val uri = resolveUri(request.tone)
            ?: error("No alarm/ringtone URI available")

        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(
                        AudioAttributes.CONTENT_TYPE_SONIFICATION
                    )
                    .build()
            )
            setDataSource(appContext, uri)
            isLooping = true
            setOnErrorListener { _, what, extra ->
                synchronized(this@AttentionSignalController) {
                    val current = active
                    if (current != null) {
                        stopInternal(
                            "PLAYBACK_ERROR",
                            false
                        )
                        callback.onFailed(
                            current,
                            "MEDIA_PLAYER_ERROR_${what}_$extra",
                            null
                        )
                    }
                }
                true
            }
            prepare()
            start()
        }
    }

    private fun resolveUri(tone: AttentionTone): Uri? {
        val type = when (tone) {
            AttentionTone.ATTENTION ->
                RingtoneManager.TYPE_NOTIFICATION
            AttentionTone.RINGTONE ->
                RingtoneManager.TYPE_RINGTONE
            AttentionTone.ALARM ->
                RingtoneManager.TYPE_ALARM
            AttentionTone.SIREN ->
                RingtoneManager.TYPE_ALARM
        }

        return RingtoneManager
            .getActualDefaultRingtoneUri(appContext, type)
            ?: RingtoneManager.getDefaultUri(type)
            ?: RingtoneManager.getDefaultUri(
                RingtoneManager.TYPE_ALARM
            )
    }

    private fun setTemporaryAlarmVolume(percent: Int) {
        val max = audioManager
            .getStreamMaxVolume(AudioManager.STREAM_ALARM)
            .coerceAtLeast(1)

        previousAlarmVolume = audioManager
            .getStreamVolume(AudioManager.STREAM_ALARM)

        val value = (
            max * percent.coerceIn(0, 100) / 100f
        ).toInt().coerceIn(0, max)

        audioManager.setStreamVolume(
            AudioManager.STREAM_ALARM,
            value,
            0
        )
    }

    private fun restoreAlarmVolume() {
        val old = previousAlarmVolume ?: return
        previousAlarmVolume = null

        runCatching {
            val max = audioManager
                .getStreamMaxVolume(
                    AudioManager.STREAM_ALARM
                )

            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                old.coerceIn(0, max),
                0
            )
        }
    }

    private fun startVibration(
        request: AttentionSignalRequest
    ) {
        if (!request.vibrate ||
            request.vibrationPattern ==
            AttentionVibrationPattern.OFF
        ) return

        vibrator =
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {
                val manager = appContext.getSystemService(
                    Context.VIBRATOR_MANAGER_SERVICE
                ) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(
                    Context.VIBRATOR_SERVICE
                ) as Vibrator
            }

        val pattern = when (
            request.vibrationPattern
        ) {
            AttentionVibrationPattern.OFF ->
                longArrayOf(0)
            AttentionVibrationPattern.PULSE ->
                longArrayOf(0, 500, 500)
            AttentionVibrationPattern.URGENT ->
                longArrayOf(
                    0, 300, 150, 300, 150, 700
                )
            AttentionVibrationPattern.SOS ->
                longArrayOf(
                    0,
                    200, 150, 200, 150, 200, 350,
                    600, 150, 600, 150, 600, 350,
                    200, 150, 200, 150, 200, 700
                )
        }

        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(
                    pattern,
                    0
                ),
                AudioAttributes.Builder()
                    .setUsage(
                        AudioAttributes.USAGE_ALARM
                    )
                    .build()
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun acquireWakeLock(durationMs: Long) {
        val manager = appContext.getSystemService(
            Context.POWER_SERVICE
        ) as PowerManager

        wakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ChildWatch:AttentionSignal"
        ).apply {
            setReferenceCounted(false)
            acquire(
                (durationMs + 10_000L)
                    .coerceAtMost(90_000L)
            )
        }
    }

    private fun stopInternal(
        reason: String,
        notify: Boolean
    ) {
        if (!stopping.compareAndSet(false, true)) {
            return
        }

        val request = active ?: run {
            stopping.set(false)
            return
        }

        cleanup()
        active = null
        stopping.set(false)

        if (notify) {
            callback.onStopped(request, reason)
        }
    }

    private fun cleanup() {
        timeout?.let(handler::removeCallbacks)
        timeout = null

        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null

        runCatching { vibrator?.cancel() }
        vibrator = null

        restoreAlarmVolume()

        runCatching {
            wakeLock
                ?.takeIf { it.isHeld }
                ?.release()
        }
        wakeLock = null
    }
}
