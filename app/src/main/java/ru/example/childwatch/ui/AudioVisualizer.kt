package ru.example.childwatch.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/**
 * Audio Visualizer View - показывает активность аудио в реальном времени
 * 
 * Features:
 * - Анимированные полосы эквалайзера
 * - Цветовая индикация активности
 * - Плавные переходы
 * - Настраиваемые параметры
 */
class AudioVisualizer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val DEFAULT_BAR_COUNT = 8
        private const val DEFAULT_BAR_WIDTH = 20f
        private const val DEFAULT_BAR_SPACING = 8f
        private const val DEFAULT_ANIMATION_DURATION = 100L
        private const val DEFAULT_MAX_HEIGHT_RATIO = 0.8f
    }

    // Параметры эквалайзера
    private var barCount = DEFAULT_BAR_COUNT
    private var barWidth = DEFAULT_BAR_WIDTH
    private var barSpacing = DEFAULT_BAR_SPACING
    private var animationDuration = DEFAULT_ANIMATION_DURATION
    private var maxHeightRatio = DEFAULT_MAX_HEIGHT_RATIO

    // Состояние
    private var isActive = false
    private var barHeights = FloatArray(barCount) { 0f }
    private var targetHeights = FloatArray(barCount) { 0f }

    // Анимация
    private var animator: ValueAnimator? = null

    // Кисти и краски
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Цвета
    private val inactiveColor = Color.parseColor("#E0E0E0")
    private val activeColor = Color.parseColor("#6750A4")
    private val recordingColor = Color.parseColor("#FF5722")

    init {
        setupPaints()
        startAnimation()
    }

    private fun setupPaints() {
        // Неактивное состояние
        inactivePaint.color = inactiveColor
        inactivePaint.style = Paint.Style.FILL

        // Активное состояние (прослушка)
        activePaint.color = activeColor
        activePaint.style = Paint.Style.FILL

        // Запись
        paint.color = recordingColor
        paint.style = Paint.Style.FILL
    }

    /**
     * Устанавливает активность эквалайзера
     */
    fun setActive(active: Boolean) {
        isActive = active
        if (active) {
            generateRandomHeights()
        } else {
            // Плавно опускаем все полосы
            targetHeights.fill(0f)
        }
    }

    /**
     * Устанавливает режим записи
     */
    fun setRecordingMode(recording: Boolean) {
        paint.color = if (recording) recordingColor else activeColor
    }

    /**
     * Генерирует случайные высоты для полос
     */
    private fun generateRandomHeights() {
        if (!isActive) return

        for (i in 0 until barCount) {
            // Генерируем случайную высоту с учетом позиции полосы
            val baseHeight = 0.3f + (i.toFloat() / barCount) * 0.4f
            val randomFactor = 0.5f + Math.random().toFloat() * 0.5f
            targetHeights[i] = baseHeight * randomFactor
        }

        // Планируем следующее обновление
        postDelayed({ generateRandomHeights() }, animationDuration + (Math.random() * 200).toLong())
    }

    /**
     * Запускает анимацию
     */
    private fun startAnimation() {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = animationDuration
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                updateBarHeights(progress)
                invalidate()
            }
            start()
        }
    }

    /**
     * Обновляет высоты полос с плавной анимацией
     */
    private fun updateBarHeights(progress: Float) {
        for (i in 0 until barCount) {
            val currentHeight = barHeights[i]
            val targetHeight = targetHeights[i]
            barHeights[i] = currentHeight + (targetHeight - currentHeight) * progress * 0.1f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2f

        // Вычисляем общую ширину всех полос
        val totalBarWidth = barCount * barWidth + (barCount - 1) * barSpacing
        val startX = (width - totalBarWidth) / 2f

        // Рисуем полосы
        for (i in 0 until barCount) {
            val x = startX + i * (barWidth + barSpacing)
            val barHeight = barHeights[i] * height * maxHeightRatio
            val top = centerY - barHeight / 2f
            val bottom = centerY + barHeight / 2f

            // Выбираем кисть в зависимости от состояния
            val currentPaint = when {
                !isActive -> inactivePaint
                paint.color == recordingColor -> paint
                else -> activePaint
            }

            // Рисуем полосу с закругленными углами
            val rect = RectF(x, top, x + barWidth, bottom)
            canvas.drawRoundRect(rect, barWidth / 4f, barWidth / 4f, currentPaint)
        }

        // Добавляем эффект свечения для активного состояния
        if (isActive) {
            drawGlowEffect(canvas, width, height, centerY, startX)
        }
    }

    /**
     * Рисует эффект свечения вокруг активных полос
     */
    private fun drawGlowEffect(canvas: Canvas, width: Float, height: Float, centerY: Float, startX: Float) {
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (paint.color == recordingColor) recordingColor else activeColor
            alpha = 30
            maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
        }

        for (i in 0 until barCount) {
            if (barHeights[i] > 0.1f) {
                val x = startX + i * (barWidth + barSpacing)
                val barHeight = barHeights[i] * height * maxHeightRatio
                val top = centerY - barHeight / 2f
                val bottom = centerY + barHeight / 2f

                val rect = RectF(x - 5f, top - 5f, x + barWidth + 5f, bottom + 5f)
                canvas.drawRoundRect(rect, barWidth / 4f, barWidth / 4f, glowPaint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    /**
     * Настраивает параметры эквалайзера
     */
    fun configure(
        barCount: Int = this.barCount,
        barWidth: Float = this.barWidth,
        barSpacing: Float = this.barSpacing,
        animationDuration: Long = this.animationDuration,
        maxHeightRatio: Float = this.maxHeightRatio
    ) {
        this.barCount = barCount
        this.barWidth = barWidth
        this.barSpacing = barSpacing
        this.animationDuration = animationDuration
        this.maxHeightRatio = maxHeightRatio

        // Пересоздаем массивы
        barHeights = FloatArray(barCount) { 0f }
        targetHeights = FloatArray(barCount) { 0f }

        invalidate()
    }
}
