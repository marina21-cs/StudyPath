package com.studypath.app.domain

import java.util.UUID

data class ReviewerPage(val number:Int, val text:String)
data class ReviewerCard(val id:String, val page:Int, val prompt:String, val answer:String, val excerpt:String, val kind:String="CLOZE")
data class ReviewerResult(val understood:Boolean, val attempts:Int)
data class PdfReviewer(val materialId:String, val pages:List<ReviewerPage>, val cards:List<ReviewerCard>, val results:Map<String,ReviewerResult> = emptyMap()) {
    fun validate() {
        require(pages.size in 1..5 && pages.map { it.number }.distinct().size==pages.size) { "Choose 1–5 distinct source pages." }
        require(pages.all { it.number in 1..500 && it.text.length<=12000 }) { "Each source page must be at most 12,000 characters." }
        require(cards.size in 1..40 && cards.map { it.id }.distinct().size==cards.size) { "Keep 1–40 distinct review cards." }
        require(cards.all { it.id.isNotBlank() && it.page in pages.map { p -> p.number } && it.prompt.isNotBlank() && it.prompt.length<=1000 && it.answer.isNotBlank() && it.answer.length<=2000 && it.excerpt.length<=600 && it.kind in listOf("CLOZE","IDENTIFICATION","MANUAL","IMAGE") }) { "Each card needs a question, answer and selected source page." }
        require(results.keys.all { id -> cards.any { it.id==id } } && results.values.all { it.attempts in 1..100000 }) { "Review progress must belong to an existing card." }
    }
    fun record(cardId:String,understood:Boolean):PdfReviewer {
        require(cards.any { it.id==cardId })
        return copy(results=results+(cardId to ReviewerResult(understood,((results[cardId]?.attempts?:0)+1).coerceAtMost(100000))))
    }
    fun retryIds() = cards.filter { results[it.id]?.understood==false }.map { it.id }
}

/** Extractive practice drafts only. No generated facts, distractors, summaries or model service. */
object ReviewerDrafts {
    private val definition=Regex("^([A-Za-z][A-Za-z0-9 -]{2,60}?)\\s+(?:is|are|means|refers to)\\s+(.{12,400})[.!?]?$",RegexOption.IGNORE_CASE)
    private val word=Regex("\\b[A-Za-z]{6,24}\\b")
    private val ignored=setOf("because","however","therefore","should","between","through","following","example","figure","chapter","these","which","within","without")
    fun create(pages:List<ReviewerPage>,limit:Int=12):List<ReviewerCard> {
        require(pages.size in 1..5 && pages.all { it.number in 1..500 && it.text.length<=12000 } && limit in 1..40)
        val cards=mutableListOf<ReviewerCard>();val seen=mutableSetOf<String>()
        for(page in pages) {
            val normalized=page.text.replace(Regex("\\s+")," ").trim()
            for(sentence in normalized.split(Regex("(?<=[.!?])\\s+"))) {
                if(sentence.length !in 35..500 || sentence.count { it.isLetter() }<sentence.length/2) continue
                val match=definition.matchEntire(sentence)
                val answer=match?.groupValues?.get(1)?.trim() ?: word.findAll(sentence).map { it.value }.filter { it.lowercase() !in ignored }.maxByOrNull { it.length } ?: continue
                if(!seen.add(answer.lowercase())) continue
                val prompt=if(match!=null) "Which term matches this description?\n"+match.groupValues[2] else sentence.replace(Regex("\\b"+Regex.escape(answer)+"\\b",RegexOption.IGNORE_CASE),"____")
                cards+=ReviewerCard(UUID.randomUUID().toString(),page.number,prompt,answer,sentence,if(match!=null) "IDENTIFICATION" else "CLOZE")
                if(cards.size==limit) return cards
            }
        }
        return cards
    }
}
