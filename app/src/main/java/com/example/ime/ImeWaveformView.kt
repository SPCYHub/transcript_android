package com.example.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import kotlin.math.abs

/**
 * High-contrast 28-bar symmetrical acoustic waveform view for the system CantoScribe Voice IME.
 * Mirrors the Studio LiveWaveformVisualizer aesthetic with rounded pill bars and center baseline.
 */
class ImeWaveformView(context: Context) : View(context) {

    private val barCount = 28
    private val displayBars = FloatArray(barCount) { idleEnvelope(it) }
    private var isLive = false

    private var activeColor = Color.parseColor("#E11D48")
    private var softActiveColor = Color.parseColor("#66E11D48")
    private var idleBarColor = Color.parseColor("#662DD4BF")
    private var baselineColor = Color.parseColor("#3394A7BC")

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1f)
    }
    private val barRect = RectF()

    fun applyPalette(
        activeCrimson: Int,
        idleAccent: Int,
        trackColor: Int
    ) {
        activeColor = activeCrimson
        softActiveColor = withAlpha(activeCrimson, 0.42f)
        idleBarColor = withAlpha(idleAccent, 0.36f)
        baselineColor = withAlpha(trackColor, 0.30f)
        invalidate()
    }

    fun updateWaveform(amplitudes: List<Float>, live: Boolean) {
        isLive = live
        if (!live) {
            for (i in 0 until barCount) {
                val target = idleEnvelope(i)
                displayBars[i] = displayBars[i] * 0.65f + target * 0.35f
            }
            invalidate()
            return
        }

        if (amplitudes.isEmpty()) {
            for (i in 0 until barCount) {
                displayBars[i] = 0.14f
            }
        } else {
            val srcSize = amplitudes.size
            for (i in 0 until barCount) {
                val srcIndex = ((i.toFloat() / (barCount - 1)) * (srcSize - 1)).toInt()
                    .coerceIn(0, srcSize - 1)
                val target = amplitudes[srcIndex].coerceIn(0.08f, 1f)
                displayBars[i] = displayBars[i] * 0.38f + target * 0.62f
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val centerY = h / 2f
        linePaint.color = baselineColor
        canvas.drawLine(0f, centerY, w, centerY, linePaint)

        val gap = dpToPx(3.5f)
        val barWidth = ((w - gap * (barCount - 1)) / barCount).coerceAtLeast(dpToPx(2.2f))
        val radius = barWidth / 2f
        val maxHalf = (h * 0.44f).coerceAtLeast(dpToPx(4f))
        val minHalf = dpToPx(2.5f)

        for (i in 0 until barCount) {
            val level = displayBars[i].coerceIn(0.08f, 1f)
            val halfH = (level * maxHalf).coerceAtLeast(minHalf)
            val left = i * (barWidth + gap)
            barPaint.color = when {
                isLive && level > 0.22f -> activeColor
                isLive -> softActiveColor
                else -> idleBarColor
            }
            barRect.set(left, centerY - halfH, left + barWidth, centerY + halfH)
            canvas.drawRoundRect(barRect, radius, radius, barPaint)
        }
    }

    private fun idleEnvelope(index: Int): Float {
        val center = (barCount - 1) / 2f
        val dist = abs(index - center) / center
        return (0.32f * (1f - dist * 0.65f)).coerceAtLeast(0.10f)
    }

    private fun withAlpha(color: Int, fraction: Float): Int {
        val alpha = (255 * fraction).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
    }
}
