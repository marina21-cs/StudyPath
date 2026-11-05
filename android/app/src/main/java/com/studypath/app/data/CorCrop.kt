package com.studypath.app.data

/** Normalized, validated crop independent of screen size and image resolution. */
data class CorCrop(val left: Float = .1f, val top: Float = .2f, val right: Float = .9f, val bottom: Float = .8f) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f }) { "Use percentages from 0 to 100." }
        require(right - left >= .02f && bottom - top >= .02f) { "Select an area at least 2% wide and high." }
    }
    fun pixels(width: Int, height: Int): IntArray {
        require(width > 0 && height > 0)
        val x = (left * width).toInt().coerceIn(0, width - 1)
        val y = (top * height).toInt().coerceIn(0, height - 1)
        return intArrayOf(x, y, ((right * width).toInt() - x).coerceIn(1, width - x), ((bottom * height).toInt() - y).coerceIn(1, height - y))
    }
}
