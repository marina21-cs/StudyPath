package com.studypath.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.studypath.app.R

/** Four native labeled destinations; selection state updates independently of drawing. */
class CapsuleBottomNavigationView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    BottomNavigationView(context, attrs) {
    private val capsule = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.tint(R.color.sp_action) }
    private var selected = -1
    private var targetLeft = Float.NaN
    private var targetRight = Float.NaN
    private var targetTop = 0f
    private var targetBottom = 0f
    private var animator: ValueAnimator? = null

    override fun setOnItemSelectedListener(listener: NavigationBarView.OnItemSelectedListener?) {
        super.setOnItemSelectedListener { item ->
            val accepted = listener?.onNavigationItemSelected(item) ?: true
            // Material checks the item after its callback returns.
            if (accepted) post { updateCapsule(true) }
            accepted
        }
    }
    override fun setSelectedItemId(itemId: Int) {
        super.setSelectedItemId(itemId)
        post { updateCapsule(true) }
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateCapsule(!changed)
    }
    private fun updateCapsule(animate: Boolean) {
        val items = getChildAt(0) as? ViewGroup ?: return
        val index = (0 until menu.size()).firstOrNull { menu.getItem(it).isChecked } ?: 0
        val item = items.getChildAt(index) ?: return
        if (item.width == 0) return
        val left = items.left + item.left + context.dp(2).toFloat()
        val right = items.left + item.right - context.dp(2).toFloat()
        val top = items.top + item.top + context.dp(4).toFloat()
        val bottom = items.top + item.bottom - context.dp(4).toFloat()
        if (selected == index && targetLeft == left && targetRight == right && targetTop == top && targetBottom == bottom) return
        animator?.cancel()
        val first = selected < 0 || capsule.isEmpty
        selected = index; targetLeft = left; targetRight = right; targetTop = top; targetBottom = bottom
        if (first || !animate || !ValueAnimator.areAnimatorsEnabled()) {
            capsule.set(left, top, right, bottom)
            invalidate()
        } else {
            val fromLeft = capsule.left; val fromRight = capsule.right
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220
                interpolator = PathInterpolator(.23f, 1f, .32f, 1f)
                addUpdateListener {
                    val fraction = it.animatedValue as Float
                    capsule.set(fromLeft + (left-fromLeft)*fraction, top, fromRight + (right-fromRight)*fraction, bottom)
                    invalidate()
                }
                start()
            }
        }
    }
    override fun dispatchDraw(canvas: Canvas) {
        if (!ValueAnimator.areAnimatorsEnabled() && !targetLeft.isNaN()) {
            animator?.cancel(); capsule.set(targetLeft, targetTop, targetRight, targetBottom)
        }
        canvas.drawRoundRect(capsule, context.dp(28).toFloat(), context.dp(28).toFloat(), paint)
        super.dispatchDraw(canvas)
    }
    override fun onDetachedFromWindow() { animator?.cancel(); animator = null; selected = -1; super.onDetachedFromWindow() }
}
