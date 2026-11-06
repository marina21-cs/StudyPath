package com.studypath.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.appcompat.content.res.AppCompatResources
import com.studypath.app.R

/** The approved original giraffe, rendered as native vector layers. Motion always settles. */
class GiraffeMascotView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class Pose { SLEEP, WAKE, WAVE, READ, CELEBRATE }
    private fun layer(id: Int): Drawable = requireNotNull(AppCompatResources.getDrawable(context, id)).mutate().apply { setBounds(0, 0, 240, 300) }
    private val book = layer(R.drawable.giraffe_book)
    private val stars = layer(R.drawable.giraffe_stars)
    private val shadow = layer(R.drawable.giraffe_shadow)
    private val tail = layer(R.drawable.giraffe_tail)
    private val torso = layer(R.drawable.giraffe_torso)
    private val arm = layer(R.drawable.giraffe_arm)
    private val head = layer(R.drawable.giraffe_head)
    private val earLeft = layer(R.drawable.giraffe_ear_left)
    private val earRight = layer(R.drawable.giraffe_ear_right)
    private val eyesOpen = layer(R.drawable.giraffe_eyes_open)
    private val eyesClosed = layer(R.drawable.giraffe_eyes_closed)
    private var animator: ValueAnimator? = null
    private var pose = Pose.WAVE
    private var progress = 1f
    private var requested = false
    private var requestedDuration: Long? = null
    private val motionObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { if (!ValueAnimator.areAnimatorsEnabled()) settle() }
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun play(pose: Pose, durationMillis: Long? = null) {
        requestedDuration = durationMillis
        animator?.cancel()
        this.pose = pose
        requested = true
        if (!isAttachedToWindow || windowVisibility != VISIBLE || !ValueAnimator.areAnimatorsEnabled()) { settle(); return }
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMillis?.coerceIn(200L, 1500L) ?: when (pose) { Pose.SLEEP -> 1250L; Pose.WAKE -> 780L; Pose.WAVE, Pose.CELEBRATE -> 1050L; Pose.READ -> 700L }
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    private fun settle() { animator?.cancel(); animator = null; progress = 1f; invalidate() }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, motionObserver)
        if (requested) play(pose, requestedDuration)
    }
    override fun onDetachedFromWindow() { settle(); context.contentResolver.unregisterContentObserver(motionObserver); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (visibility != VISIBLE) settle() }
    private val ease = PathInterpolator(.23f, 1f, .32f, 1f)
    private fun key(vararg values: Float): Float {
        val p = progress.coerceIn(0f, 1f) * (values.size - 1)
        val index = p.toInt().coerceAtMost(values.lastIndex - 1)
        return values[index] + (values[index + 1] - values[index]) * ease.getInterpolation(p - index)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf((width-paddingLeft-paddingRight)/240f, (height-paddingTop-paddingBottom)/300f)
        if (scale <= 0f) return
        canvas.save()
        canvas.translate(paddingLeft + (width-paddingLeft-paddingRight-240*scale)/2, paddingTop + (height-paddingTop-paddingBottom-300*scale)/2)
        canvas.scale(scale, scale)
        shadow.draw(canvas)
        canvas.save()
        if (pose == Pose.WAKE) canvas.translate(0f, key(2f, -3f, 0f))
        if (pose == Pose.CELEBRATE) { stars.draw(canvas); canvas.translate(0f, key(0f,-8f,0f,-5f,0f)) }
        tail.draw(canvas)
        canvas.save()
        if (pose == Pose.SLEEP) canvas.scale(1f, key(1f, 1.018f, 1f), 120f, 270f)
        torso.draw(canvas); canvas.restore()
        canvas.save()
        canvas.rotate(when(pose) { Pose.SLEEP -> 18f; Pose.WAVE, Pose.CELEBRATE -> key(0f,-12f,12f,-9f,0f); Pose.READ -> 35f; else -> key(18f,0f) }, 151f, 215f)
        arm.draw(canvas); canvas.restore()
        canvas.save()
        canvas.translate(0f, when(pose) { Pose.SLEEP -> 3f; Pose.WAKE -> key(3f,-2f,0f); else -> 0f })
        canvas.rotate(when(pose) { Pose.SLEEP -> -7f; Pose.WAKE -> key(-7f,3f,0f); Pose.WAVE, Pose.CELEBRATE -> key(0f,-3f,0f); Pose.READ -> 5f }, 121f,150f)
        canvas.save(); canvas.rotate(if(pose==Pose.WAKE) key(-10f,8f,0f) else 0f,82f,78f); earLeft.draw(canvas); canvas.restore()
        canvas.save(); canvas.rotate(if(pose==Pose.WAKE) key(10f,-8f,0f) else 0f,158f,78f); earRight.draw(canvas); canvas.restore()
        head.draw(canvas)
        val closed = pose == Pose.SLEEP || (pose == Pose.WAKE && progress < .23f) || (pose == Pose.WAVE && progress in .63f.. .75f)
        (if(closed) eyesClosed else eyesOpen).draw(canvas)
        canvas.restore()
        if (pose == Pose.READ) book.draw(canvas)
        canvas.restore(); canvas.restore()
    }
}
