package com.studypath.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.PathInterpolator
import com.google.android.material.button.MaterialButton
import com.studypath.app.R

/** A raised primary face inside a fixed native button hit area. Clicks remain Material's. */
class StudyButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = context.dp(2).toFloat()
    }
    private val depth = context.dp(4).toFloat()
    private var ready = false
    private var touching = false
    private var motion: ValueAnimator? = null
    internal var faceOffset = 0f
        private set
    private val raised: Boolean
        get() = backgroundTintList?.defaultColor == context.tint(R.color.sp_action) && strokeWidth == 0

    init {
        stateListAnimator = null
        elevation = 0f
        ready = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) touching = true
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) touching = false
        return handled
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        if (!ready) return
        motion?.cancel()
        val animate = touching && isEnabled && raised && ValueAnimator.areAnimatorsEnabled()
        val target = if (pressed && animate) depth else 0f
        if (!animate || !isAttachedToWindow) {
            faceOffset = 0f
            invalidate()
            return
        }
        motion = ValueAnimator.ofFloat(faceOffset, target).apply {
            duration = if (pressed) 120L else 150L
            interpolator = PathInterpolator(.23f, 1f, .32f, 1f)
            addUpdateListener { faceOffset = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (ready && !enabled) settle()
    }

    private fun settle() {
        motion?.cancel(); motion = null; faceOffset = 0f; touching = false
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        if (!ValueAnimator.areAnimatorsEnabled() && faceOffset != 0f) settle()
        val isRaised = raised && isEnabled
        val radius = cornerRadius.toFloat().coerceAtLeast(0f)
        if (isRaised) {
            edgePaint.color = context.tint(R.color.sp_action_edge)
            canvas.drawRoundRect(0f, depth, width.toFloat(), height.toFloat(), radius, radius, edgePaint)
        }
        val save = canvas.save()
        if (isRaised) canvas.translate(0f, faceOffset)
        super.draw(canvas)
        if (isFocused && isEnabled) {
            focusPaint.color = context.tint(if (isRaised) R.color.sp_card else R.color.sp_action)
            val inset = context.dp(3).toFloat()
            canvas.drawRoundRect(inset, inset + insetTop, width - inset, height - insetBottom - inset,
                (radius - inset).coerceAtLeast(0f), (radius - inset).coerceAtLeast(0f), focusPaint)
        }
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() { settle(); super.onDetachedFromWindow() }
}
