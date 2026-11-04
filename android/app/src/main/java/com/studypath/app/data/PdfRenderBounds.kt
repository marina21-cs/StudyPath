package com.studypath.app.data

/** Bounds app-owned PDF raster memory on the API 26 baseline. */
object PdfRenderBounds {
    fun fit(pageWidth: Int, pageHeight: Int, requestedWidth: Int): Pair<Int, Int> {
        require(pageWidth in 1..14_400 && pageHeight in 1..14_400 && requestedWidth > 0)
        val scale = minOf(requestedWidth.coerceAtMost(1600).toDouble() / pageWidth, 4096.0 / pageHeight,
            kotlin.math.sqrt(4_000_000.0 / (pageWidth.toDouble() * pageHeight)))
        return maxOf(1, (pageWidth * scale).toInt()) to maxOf(1, (pageHeight * scale).toInt())
    }
}
