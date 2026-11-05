package com.studypath.app.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.studypath.app.R
import com.studypath.app.data.CorCrop
import kotlin.math.*

/** Drag a corner; the adjacent Edit crop button offers equivalent numeric controls. */
class CorCropView(context: Context, private val image: Bitmap, initial: CorCrop, private val changed: (CorCrop) -> Unit) : View(context) {
    var crop = initial; private set
    private val page = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var corner = -1
    init { contentDescription = "Course-name selection. Drag a corner, or use Edit crop percentages below."; minimumHeight = context.dp(300) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width.toFloat() / image.width, height.toFloat() / image.height)
        val w=image.width*scale; val h=image.height*scale
        page.set((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
        paint.color=Color.WHITE; paint.style=Paint.Style.FILL; canvas.drawRect(page,paint)
        canvas.drawBitmap(image,null,page,paint)
        val r=RectF(page.left+crop.left*w,page.top+crop.top*h,page.left+crop.right*w,page.top+crop.bottom*h)
        paint.color=0x99203452.toInt()
        canvas.drawRect(page.left,page.top,page.right,r.top,paint); canvas.drawRect(page.left,r.bottom,page.right,page.bottom,paint)
        canvas.drawRect(page.left,r.top,r.left,r.bottom,paint); canvas.drawRect(r.right,r.top,page.right,r.bottom,paint)
        paint.color=context.tint(R.color.sp_action); paint.style=Paint.Style.STROKE; paint.strokeWidth=context.dp(3).toFloat(); canvas.drawRect(r,paint)
        paint.style=Paint.Style.FILL
        listOf(r.left to r.top,r.right to r.top,r.left to r.bottom,r.right to r.bottom).forEach { (x,y) -> canvas.drawCircle(x,y,context.dp(10).toFloat(),paint) }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (page.width() <= 0) return false
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x=(event.x-page.left)/page.width(); val y=(event.y-page.top)/page.height()
                val corners=listOf(crop.left to crop.top,crop.right to crop.top,crop.left to crop.bottom,crop.right to crop.bottom)
                corner=corners.indices.minBy { hypot((corners[it].first-x)*page.width(),(corners[it].second-y)*page.height()) }
                parent.requestDisallowInterceptTouchEvent(true); return true
            }
            MotionEvent.ACTION_MOVE -> if(corner>=0) {
                val x=((event.x-page.left)/page.width()).coerceIn(0f,1f); val y=((event.y-page.top)/page.height()).coerceIn(0f,1f)
                crop=CorCrop(if(corner%2==0) x.coerceAtMost(crop.right-.021f) else crop.left,
                    if(corner<2) y.coerceAtMost(crop.bottom-.021f) else crop.top,
                    if(corner%2==1) x.coerceAtLeast(crop.left+.021f) else crop.right,
                    if(corner>=2) y.coerceAtLeast(crop.top+.021f) else crop.bottom)
                changed(crop); invalidate(); return true
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { corner=-1; parent.requestDisallowInterceptTouchEvent(false); if(event.actionMasked==MotionEvent.ACTION_UP) performClick(); return true }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
