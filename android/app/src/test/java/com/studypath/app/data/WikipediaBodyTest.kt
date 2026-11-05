package com.studypath.app.data
import org.junit.Test
import org.junit.Assert.*
import org.jsoup.Jsoup
class WikipediaBodyTest {
    @Test fun removesChromeAndCitationDebrisBeforeLead() {
        val text = WikipediaBody.text(Jsoup.parse("""<aside class="sidebar"><li>Artificial intelligence topics</li></aside><table class="infobox"><tr><td><p>Chinese name</p></td></tr></table><section><p>DeepSeek develops models.<sup class="mw-ref">[1]</sup></p><h2>History</h2><p>Article prose.</p><h2>References</h2><ol><li>Citation</li></ol></section>"""))
        assertEquals("DeepSeek develops models.\n\nHistory\n\nArticle prose.",text)
    }
    @Test fun preservesListsWithoutDuplicatingParentAndChildren() {
        val text=WikipediaBody.text(Jsoup.parse("<p>Intro</p><ul><li>Parent<ul><li>Child</li></ul></li><li><p>Second</p></li></ul>"))
        assertEquals("Intro\n\n• Parent\n\n  • Child\n\n• Second",text)
    }
    @Test fun retainsTexBracesOutsideDisplayWrapper() {
        val text=WikipediaBody.text(Jsoup.parse("<p><span class='mwe-math-element'><img alt='a^{2}'></span></p>"))
        assertEquals("a^{2}",text)
    }
    @Test fun preservesSingleMathAlternative() {
        val text=WikipediaBody.text(Jsoup.parse("<p>Formula <span class='mwe-math-element'><math>x x</math><img alt='x²'></span> works.</p>"))
        assertEquals("Formula x² works.",text)
    }
}
