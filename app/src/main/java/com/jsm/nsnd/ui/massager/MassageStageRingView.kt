package com.jsm.nsnd.ui.massager

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.jsm.nsnd.R

class MassageStageRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcBounds = RectF()
    private var visualStage = 1f
    private var maxStage = 5
    private var stageAnimator: ValueAnimator? = null

    fun setStage(stage: Int, maximum: Int) {
        maxStage = maximum.coerceAtLeast(1)
        val target = stage.coerceIn(1, maxStage).toFloat()
        stageAnimator?.cancel()
        stageAnimator = ValueAnimator.ofFloat(visualStage, target).apply {
            duration = 280L
            addUpdateListener {
                visualStage = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        contentDescription = "마사지 강도 ${target.toInt()}단계, 최대 ${maxStage}단계"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val levelRatio = (visualStage / maxStage).coerceIn(0f, 1f)
        val toneRatio = if (maxStage == 1) 1f else
            ((visualStage - 1f) / (maxStage - 1f)).coerceIn(0f, 1f)

        trackPaint.color = ContextCompat.getColor(context, R.color.border)
        trackPaint.strokeWidth = 5f * density

        val lightBlue = ContextCompat.getColor(context, R.color.accent_light)
        val deepBlue = ContextCompat.getColor(context, R.color.accent_primary_dark)
        progressPaint.color = ColorUtils.blendARGB(lightBlue, deepBlue, toneRatio)
        progressPaint.strokeWidth = (5f + visualStage * 1.7f) * density

        val inset = 10f * density
        arcBounds.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint)
        canvas.drawArc(arcBounds, -90f, 360f * levelRatio, false, progressPaint)
    }

    override fun onDetachedFromWindow() {
        stageAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
