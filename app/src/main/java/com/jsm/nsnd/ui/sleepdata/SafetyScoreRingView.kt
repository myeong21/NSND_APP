package com.jsm.nsnd.ui.sleepdata

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.jsm.nsnd.R

class SafetyScoreRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val bounds = RectF()
    private var displayedScore = 0f
    private var scoreColor = 0
    private var animator: ValueAnimator? = null

    fun setScore(score: Int, grade: String) {
        val target = score.coerceIn(0, 100).toFloat()
        scoreColor = ContextCompat.getColor(context, when (grade.lowercase()) {
            "danger" -> R.color.status_danger
            "caution" -> R.color.status_warn
            else -> R.color.status_safe
        })
        animator?.cancel()
        animator = ValueAnimator.ofFloat(displayedScore, target).apply {
            duration = 520L
            addUpdateListener { displayedScore = it.animatedValue as Float; invalidate() }
            start()
        }
        contentDescription = "안전 점수 ${target.toInt()}점"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val inset = 7f * density
        bounds.set(inset, inset, width - inset, height - inset)
        trackPaint.color = ContextCompat.getColor(context, R.color.border)
        trackPaint.strokeWidth = 7f * density
        scorePaint.color = if (scoreColor == 0) ContextCompat.getColor(context, R.color.status_safe) else scoreColor
        scorePaint.strokeWidth = 8f * density
        canvas.drawArc(bounds, 0f, 360f, false, trackPaint)
        canvas.drawArc(bounds, -90f, 360f * displayedScore / 100f, false, scorePaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
