package ru.example.childwatch.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import ru.example.childwatch.R
import kotlin.math.abs
import kotlin.math.min

/**
 * Custom View for real-time audio waveform visualization
 * Shows moving waveform based on incoming audio chunks
 */
class AudioWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = android.graphics.Color.parseColor("#4CAF50") // Green color
    }

    private val centerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = ContextCompat.getColor(context, android.R.color.darker_gray)
        alpha = 80
    }

    private val path = Path()
    private val amplitudeData = mutableListOf<Float>()
    private val maxDataPoints = 200 // Increased for smoother visualization
    private var maxAmplitudeRecorded = 0f

    private var isActive = false

    /**
     * Update waveform with new audio data
     * @param audioBytes Raw PCM audio bytes
     */
    fun updateWaveform(audioBytes: ByteArray) {
        if (!isActive) return

        // Calculate RMS (Root Mean Square) amplitude - более точное представление громкости
        var sumSquares = 0.0
        var count = 0

        for (i in audioBytes.indices step 2) {
            if (i + 1 < audioBytes.size) {
                // Convert two bytes to 16-bit sample (Little Endian)
                val sample = ((audioBytes[i + 1].toInt() shl 8) or (audioBytes[i].toInt() and 0xFF)).toShort()
                val normalized = sample.toFloat() / Short.MAX_VALUE
                sumSquares += (normalized * normalized)
                count++
            }
        }

        // Calculate RMS amplitude (0.0 to 1.0)
        val rms = if (count > 0) {
            kotlin.math.sqrt(sumSquares / count).toFloat()
        } else {
            0f
        }

        // Apply sensitivity boost (make quieter sounds more visible)
        val boostedAmplitude = min(rms * 2.5f, 1f)

        // Track maximum for normalization
        if (boostedAmplitude > maxAmplitudeRecorded) {
            maxAmplitudeRecorded = boostedAmplitude
        }

        // Normalize relative to max (with gradual decay)
        maxAmplitudeRecorded *= 0.995f // Slowly decay max
        val normalizedAmplitude = if (maxAmplitudeRecorded > 0.1f) {
            min(boostedAmplitude / maxAmplitudeRecorded, 1f)
        } else {
            boostedAmplitude
        }

        // Add to data and maintain max size
        amplitudeData.add(normalizedAmplitude)
        if (amplitudeData.size > maxDataPoints) {
            amplitudeData.removeAt(0)
        }

        // Trigger redraw
        invalidate()
    }

    /**
     * Start/resume waveform animation
     */
    fun start() {
        isActive = true
    }

    /**
     * Stop waveform animation
     */
    fun stop() {
        isActive = false
        amplitudeData.clear()
        invalidate()
    }

    /**
     * Clear waveform data
     */
    fun clear() {
        amplitudeData.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2f

        // Draw center line
        canvas.drawLine(0f, centerY, width, centerY, centerLinePaint)

        if (amplitudeData.isEmpty() || !isActive) {
            // Draw flat line when no data
            canvas.drawLine(0f, centerY, width, centerY, paint)
            return
        }

        // Draw waveform
        path.reset()

        val stepX = width / maxDataPoints
        val maxAmplitude = height / 2f * 0.8f // Use 80% of available height

        // Start from left
        path.moveTo(0f, centerY)

        for (i in amplitudeData.indices) {
            val x = i * stepX
            val amplitude = amplitudeData[i] * maxAmplitude

            // Create wave pattern (both positive and negative)
            val y = centerY - amplitude

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        canvas.drawPath(path, paint)

        // Draw mirrored waveform below center line
        path.reset()
        path.moveTo(0f, centerY)

        for (i in amplitudeData.indices) {
            val x = i * stepX
            val amplitude = amplitudeData[i] * maxAmplitude
            val y = centerY + amplitude

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        canvas.drawPath(path, paint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Enforce minimum height
        val minHeight = 100
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> min(minHeight, heightSize)
            else -> minHeight
        }

        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            height
        )
    }
}
