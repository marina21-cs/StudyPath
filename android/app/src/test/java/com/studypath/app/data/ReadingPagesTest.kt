package com.studypath.app.data
import org.junit.Assert.*
import org.junit.Test
class ReadingPagesTest {
    @Test fun `pagination preserves every character including long unbroken text`() {
        val text = "Learning is continuous.\n\n".repeat(800) + "x".repeat(14000) + " THE END"
        val pages = ReadingPages.split(text)
        assertEquals(text, pages.joinToString("")); assertTrue(pages.size > 2); assertTrue(pages.all { it.length <= 6000 })
        assertTrue(pages.last().endsWith("THE END"))
    }
    @Test fun `pagination does not split a unicode surrogate pair`() {
        val text = "a".repeat(5999) + "🦒" + "b".repeat(100)
        val pages = ReadingPages.split(text)
        assertEquals(text, pages.joinToString(""))
        assertFalse(pages.first().last().isHighSurrogate())
    }
    @Test fun `offline excerpt ends at a complete paragraph when available`() {
        val paragraph = "A complete thought. ".repeat(300)
        val excerpt = ReadingPages.excerpt(paragraph + "\n\n" + "More text. ".repeat(500), 8000)
        assertEquals(paragraph.trim(), excerpt)
    }
}
