package ru.example.childwatch.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import ru.example.childwatch.R
import kotlin.math.*

/**
 * Advanced Audio Visualizer with multiple visualization modes
 * Supports waveform, frequency bars, and volume meter
 */
class AdvancedAudioVisualizer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class VisualizationMode {
        WAVEFORM,      // Classic waveform
        FREQUENCY_BARS, // Equalizer bars
        VOLUME_METER,  // Simple volume bar
        CIRCULAR       // Circular visualization
    }

    private var currentMode = VisualizationMode.FREQUENCY_BARS
    
    // Paints
    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#4CAF50")
    }
    
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4CAF50")
    }
    
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1A1A1A")
    }
    
    private val centerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.GRAY
        alpha = 80
    }

    // Data
    private val amplitudeData = mutableListOf<Float>()
    private val frequencyData = FloatArray(32) // 32 frequency bands
    private val maxDataPoints = 200
    private var maxAmplitudeRecorded = 0f
    private var currentVolume = 0f
    
    private var isActive = false
    private var animationOffset = 0f

    /**
     * Update visualization with new audio data
     */
    fun updateVisualization(audioBytes: ByteArray) {
        if (!isActive) return

        // Calculate RMS amplitude
        val rms = calculateRMS(audioBytes)
        
        // Calculate frequency spectrum (simplified FFT simulation)
        calculateFrequencySpectrum(audioBytes)
        
        // Update volume level
        currentVolume = rms
        
        // Add to waveform data
        amplitudeData.add(rms)
        if (amplitudeData.size > maxDataPoints) {
            amplitudeData.removeAt(0)
        }
        
        // Track maximum for normalization
        if (rms > maxAmplitudeRecorded) {
            maxAmplitudeRecorded = rms
        }
        maxAmplitudeRecorded *= 0.995f // Gradual decay
        
        invalidate()
    }
    
    private fun calculateRMS(audioBytes: ByteArray): Float {
        var sumSquares = 0.0
        var count = 0
        
        for (i in audioBytes.indices step 2) {
            if (i + 1 < audioBytes.size) {
                val sample = ((audioBytes[i + 1].toInt() shl 8) or (audioBytes[i].toInt() and 0xFF)).toShort()
                val normalized = sample.toFloat() / Short.MAX_VALUE
                sumSquares += (normalized * normalized)
                count++
            }
        }
        
        return if (count > 0) {
            sqrt(sumSquares / count).toFloat()
        } else {
            0f
        }
    }
    
    private fun calculateFrequencySpectrum(audioBytes: ByteArray) {
        // Simplified frequency analysis - in real implementation would use FFT
        val samples = audioBytes.size / 2
        val step = samples / frequencyData.size
        
        for (i in frequencyData.indices) {
            var sum = 0f
            val start = i * step
            val end = min((i + 1) * step, samples)
            
            for (j in start until end) {
                if (j * 2 + 1 < audioBytes.size) {
                    val sample = ((audioBytes[j * 2 + 1].toInt() shl 8) or (audioBytes[j * 2].toInt() and 0xFF)).toShort()
                    sum += abs(sample.toFloat() / Short.MAX_VALUE)
                }
            }
            
            frequencyData[i] = if (end > start) sum / (end - start) else 0f
        }
    }

    fun setVisualizationMode(mode: VisualizationMode) {
        currentMode = mode
        invalidate()
    }
    
    fun getCurrentMode(): VisualizationMode = currentMode
    
    fun start() {
        isActive = true
    }
    
    fun stop() {
        isActive = false
        amplitudeData.clear()
        frequencyData.fill(0f)
        currentVolume = 0f
        invalidate()
    }
    
    fun clear() {
        amplitudeData.clear()
        frequencyData.fill(0f)
        currentVolume = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val width = width.toFloat()
        val height = height.toFloat()
        
        // Draw background
        canvas.drawRect(0f, 0f, width, height, backgroundPaint)
        
        when (currentMode) {
            VisualizationMode.WAVEFORM -> drawWaveform(canvas, width, height)
            VisualizationMode.FREQUENCY_BARS -> drawFrequencyBars(canvas, width, height)
            VisualizationMode.VOLUME_METER -> drawVolumeMeter(canvas, width, height)
            VisualizationMode.CIRCULAR -> drawCircular(canvas, width, height)
        }
        
        // Update animation offset
        animationOffset += 0.1f
    }
    
    private fun drawWaveform(canvas: Canvas, width: Float, height: Float) {
        val centerY = height / 2f
        canvas.drawLine(0f, centerY, width, centerY, centerLinePaint)
        
        if (amplitudeData.isEmpty() || !isActive) {
            canvas.drawLine(0f, centerY, width, centerY, waveformPaint)
            return
        }
        
        val path = Path()
        val stepX = width / maxDataPoints
        val maxAmplitude = height / 2f * 0.8f
        
        path.moveTo(0f, centerY)
        
        for (i in amplitudeData.indices) {
            val x = i * stepX
            val amplitude = amplitudeData[i] * maxAmplitude
            val y = centerY - amplitude
            
            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        
        canvas.drawPath(path, waveformPaint)
        
        // Draw mirrored waveform
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
        
        canvas.drawPath(path, waveformPaint)
    }
    
    private fun drawFrequencyBars(canvas: Canvas, width: Float, height: Float) {
        val barWidth = width / frequencyData.size
        val maxBarHeight = height * 0.8f
        
        for (i in frequencyData.indices) {
            val barHeight = frequencyData[i] * maxBarHeight
            val x = i * barWidth
            val y = height - barHeight
            
            // Add some animation
            val animatedHeight = barHeight * (0.7f + 0.3f * sin(animationOffset + i * 0.5f))
            
            canvas.drawRect(
                x + 2f,
                height - animatedHeight,
                x + barWidth - 2f,
                height,
                barPaint
            )
        }
    }
    
    private fun drawVolumeMeter(canvas: Canvas, width: Float, height: Float) {
        val meterWidth = width * 0.8f
        val meterHeight = 20f
        val meterX = (width - meterWidth) / 2f
        val meterY = (height - meterHeight) / 2f
        
        // Background
        canvas.drawRect(meterX, meterY, meterX + meterWidth, meterY + meterHeight, centerLinePaint)
        
        // Volume level
        val volumeWidth = meterWidth * currentVolume
        canvas.drawRect(meterX, meterY, meterX + volumeWidth, meterY + meterHeight, barPaint)
        
        // Volume percentage text
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }
        
        val percentage = (currentVolume * 100).toInt()
        canvas.drawText(
            "$percentage%",
            width / 2f,
            meterY - 10f,
            textPaint
        )
    }
    
    private fun drawCircular(canvas: Canvas, width: Float, height: Float) {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width, height) / 2f * 0.8f
        
        // Draw frequency bars in circular pattern
        val angleStep = 2f * PI / frequencyData.size
        
        for (i in frequencyData.indices) {
            val angle = i * angleStep + animationOffset
            val barLength = frequencyData[i] * radius * 0.8f
            
            val startX = centerX + cos(angle).toFloat() * radius
            val startY = centerY + sin(angle).toFloat() * radius
            val endX = centerX + cos(angle).toFloat() * (radius + barLength)
            val endY = centerY + sin(angle).toFloat() * (radius + barLength)
            
            barPaint.strokeWidth = 4f
            barPaint.style = Paint.Style.STROKE
            canvas.drawLine(startX, startY, endX, endY, barPaint)
        }
        
        // Draw center circle
        canvas.drawCircle(centerX, centerY, radius * 0.2f, centerLinePaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minHeight = 120
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
