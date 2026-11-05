package com.studypath.app.data

import org.jsoup.nodes.Document

/** MediaWiki/Parsoid article blocks; keep prose and list structure, not site chrome. */
object WikipediaBody {
    fun text(document: Document): String {
        val body = document.clone()
        body.select("script,style,form,iframe,frame,object,embed,video,audio,canvas,input,button,textarea,noscript," +
            "nav,aside,.infobox,.sidebar,.navbox,.vertical-navbox,.metadata,.ambox,.hatnote,.shortdescription," +
            ".mw-editsection,.mw-empty-elt,.toc,.reflist,.references,.mw-references-wrap,.reference," +
            ".mw-ref,.noprint,.authority-control,.catlinks,.sistersitebox,[role=navigation]").remove()
        // Preserve one readable representation of formulas, rather than dumping MathML and TeX twice.
        body.select(".mwe-math-element").forEach { math ->
            val alternative = math.selectFirst("img[alt]")?.attr("alt")
                ?: math.selectFirst("annotation[encoding=application/x-tex]")?.text()
                ?: "[Formula — open the original source]"
            math.text(if (alternative.startsWith("{\\displaystyle ") && alternative.endsWith("}")) alternative.removePrefix("{\\displaystyle ").dropLast(1) else alternative)
        }
        body.select("img,svg,math").remove()
        val root = body.selectFirst(".mw-parser-output") ?: body.body()
        val blocks = mutableListOf<String>()
        var omitSection = false
        root.select("h2,h3,h4,p,li").forEach { element ->
            val tag = element.tagName()
            if (tag == "h2") omitSection = element.text().trim().lowercase() in setOf("references", "external links", "notes", "further reading", "bibliography", "see also")
            if (omitSection || element.parents().any { it.tagName() == "li" || it.tagName() == "table" }) return@forEach
            val copy = element.clone()
            if (tag == "li") copy.select("ul,ol").remove()
            val text = copy.text().replace(Regex("\\s+"), " ").trim()
            if (text.isNotEmpty()) blocks += if (tag == "li") "• $text" else text
            if (tag == "li") element.select("li").drop(1).forEach { child ->
                val item = child.clone().apply { select("ul,ol").remove() }.text().trim()
                if (item.isNotEmpty()) blocks += "  • $item"
            }
        }
        return blocks.joinToString("\n\n")
    }
}
