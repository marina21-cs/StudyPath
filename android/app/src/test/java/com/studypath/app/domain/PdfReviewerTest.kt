package com.studypath.app.domain

import org.junit.Assert.*
import org.junit.Test

class PdfReviewerTest {
    private val pages=listOf(ReviewerPage(2,"A database is an organized collection of information stored for retrieval. A queue processes elements in the order they arrive."))
    @Test fun draftsKeepTheirAnswersAndSourcePages() {
        val cards=ReviewerDrafts.create(pages)
        assertEquals(2,cards.size);assertEquals("IDENTIFICATION",cards.first().kind)
        cards.forEach { assertEquals(2,it.page);assertTrue(pages.single().text.contains(it.excerpt));assertTrue(it.excerpt.contains(it.answer,true));assertFalse(it.prompt.contains(it.answer,true)) }
        PdfReviewer("material",pages,cards).validate()
    }
    @Test fun emptyOrSymbolOnlyScansRequireManualCards() {
        assertTrue(ReviewerDrafts.create(listOf(ReviewerPage(1,"123 + 456 = 579"))).isEmpty())
        assertTrue(ReviewerDrafts.create(listOf(ReviewerPage(1,""))).isEmpty())
    }
    @Test fun duplicateAnswersAndLimitsDoNotProduceRepeatedCards() {
        val cards=ReviewerDrafts.create(pages+ReviewerPage(3,pages.single().text),1)
        assertEquals(1,cards.size)
        assertEquals(2,ReviewerDrafts.create(pages+ReviewerPage(3,pages.single().text)).size)
    }
    @Test fun progressIsSelfRatedAndRetryUsesLatestAttempt() {
        val pack=PdfReviewer("material",pages,ReviewerDrafts.create(pages));val id=pack.cards.first().id
        val missed=pack.record(id,false);assertEquals(listOf(id),missed.retryIds())
        val reviewed=missed.record(id,true);assertTrue(reviewed.retryIds().isEmpty());assertEquals(2,reviewed.results.getValue(id).attempts)
        assertTrue(pack.results.isEmpty());assertThrows(IllegalArgumentException::class.java) { pack.record("unknown",true) }
    }
    @Test fun validationRejectsDetachedSourcesAndOversizedData() {
        val cards=ReviewerDrafts.create(pages)
        assertThrows(IllegalArgumentException::class.java) { PdfReviewer("m",pages,cards.map { it.copy(page=9) }).validate() }
        assertThrows(IllegalArgumentException::class.java) { PdfReviewer("m",pages,cards, mapOf("missing" to ReviewerResult(true,1))).validate() }
        assertThrows(IllegalArgumentException::class.java) { ReviewerDrafts.create(listOf(ReviewerPage(1,"a".repeat(12001)))) }
    }
    @Test fun manualImageCardRetainsOriginalPageWithoutOcrInterpretation() {
        PdfReviewer("m",listOf(ReviewerPage(7,"")),listOf(ReviewerCard("c",7,"Explain the diagram","My confirmed answer","","IMAGE"))).validate()
    }
}
