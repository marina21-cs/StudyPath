package com.studypath.app.data

data class ArticleText(val title: String, val text: String, val sourceUrl: String, val revisionUrl: String, val attribution: String, val license: String, val licenseUrl: String)
object ReadingPages {
    /** Pages preserve every character; break at whitespace where possible. */
    fun split(text: String, limit: Int = 6000): List<String> {
        require(limit >= 100)
        val pages = mutableListOf<String>(); var start = 0
        while (start < text.length) {
            var end = (start + limit).coerceAtMost(text.length)
            if (end < text.length) {
                val space = text.lastIndexOfAny(charArrayOf(' ', '\n'), end - 1)
                if (space > start + limit / 2) end = space + 1
            }
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            pages += text.substring(start, end); start = end
        }
        return pages
    }
    fun excerpt(text: String, limit: Int): String {
        if (text.length <= limit) return text.trim()
        val candidate = text.take(limit)
        val paragraph = candidate.lastIndexOf("\n\n")
        if (paragraph > limit / 2) return candidate.take(paragraph).trim()
        val sentence = candidate.lastIndexOf(". ")
        if (sentence > limit / 2) return candidate.take(sentence + 1).trim()
        val word = candidate.lastIndexOf(' ')
        return (if (word > 0) candidate.take(word) else candidate).trim()
    }
}
