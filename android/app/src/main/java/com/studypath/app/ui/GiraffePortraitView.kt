package com.studypath.app.ui

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/** Frames the mascot's head above forms without creating a second raster asset. */
class GiraffePortraitView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) :
    AppCompatImageView(context, attrs, defStyle) {
    init { scaleType = ScaleType.MATRIX }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val image = drawable ?: return
        if (image.intrinsicWidth <= 0 || image.intrinsicHeight <= 0) return
        val scale = minOf(width.toFloat() / image.intrinsicWidth, height.toFloat() / (image.intrinsicHeight * .47f))
        imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((width - image.intrinsicWidth * scale) / 2f, 0f)
        }
    }
}
