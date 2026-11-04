package com.studypath.app.data

import org.junit.Assert.*
import org.junit.Test

class PdfRenderBoundsTest {
    @Test fun portraitLandscapeAndVeryTallPagesFitTheRasterBudgetWithoutDistortion() {
        for ((width, height) in listOf(600 to 800, 800 to 600, 100 to 14_400, 14_400 to 100)) {
            val rendered = PdfRenderBounds.fit(width, height, 1600)
            assertTrue(rendered.first in 1..1600 && rendered.second in 1..4096)
            assertTrue(rendered.first.toLong() * rendered.second <= 4_000_000)
            assertEquals(width.toDouble() / height, rendered.first.toDouble() / rendered.second, 0.02 * maxOf(1.0, width.toDouble() / height))
        }
    }
    @Test fun invalidPageDimensionsCannotAllocateABitmap() {
        for ((width, height) in listOf(0 to 100, 100 to 0, -1 to 100, 14_401 to 100))
            assertThrows(IllegalArgumentException::class.java) { PdfRenderBounds.fit(width, height, 1000) }
    }
}
