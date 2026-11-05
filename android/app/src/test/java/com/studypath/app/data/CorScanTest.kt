package com.studypath.app.data

import org.junit.Assert.*
import org.junit.Test

class CorScanTest {
    @Test fun cropMapsToImagePixelsAndKeepsOutsideTextOut() {
        assertArrayEquals(intArrayOf(100,400,800,1200), CorCrop(.1f,.2f,.9f,.8f).pixels(1000,2000))
        assertArrayEquals(intArrayOf(0,0,1,1),CorCrop(0f,0f,1f,1f).pixels(1,1))
    }
    @Test fun rejectsInvertedNonfiniteAndTinyCrops() {
        listOf({CorCrop(.9f,.2f,.1f,.8f)},{CorCrop(Float.NaN)},{CorCrop(0f,0f,.01f,1f)},{CorCrop(-.1f)}).forEach { assertThrows(IllegalArgumentException::class.java) { it() } }
    }
    @Test fun reviewedNamesDoNotInventCodesOrUnits() {
        val rows=CorImport.parseNames("  Advance   Calculus \nDatabase Systems\n")
        assertEquals(listOf("Advance Calculus","Database Systems"),rows.map { it.title })
        assertTrue(rows.all { it.code.isEmpty() && it.units==null })
    }
    @Test fun rejectDuplicateNamesNumericNoiseAndTooManyCourses() {
        listOf("Calculus\nCALCULUS","12345",(1..101).joinToString("\n") { "Course $it" }).forEach { text -> assertThrows(IllegalArgumentException::class.java) { CorImport.parseNames(text) } }
    }
}
