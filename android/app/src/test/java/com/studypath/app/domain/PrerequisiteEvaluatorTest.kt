package com.studypath.app.domain

import org.junit.Assert.*
import org.junit.Test

class PrerequisiteEvaluatorTest {
    private val catalog = setOf("A", "B", "C")
    private val a = Prerequisite.Course("A")
    private val b = Prerequisite.Course("B")
    private val c = Prerequisite.Course("C")
    private fun evaluate(expression: Prerequisite, vararg records: Pair<String, CompletionStatus>) =
        PrerequisiteEvaluator.evaluate(expression, catalog, records.toMap())

    @Test fun passedAndExplicitEquivalentSatisfyRequirement() {
        assertEquals(PrerequisiteState.MET, evaluate(a, "A" to CompletionStatus.PASSED).state)
        assertEquals(PrerequisiteState.MET, evaluate(a, "A" to CompletionStatus.CREDITED_EQUIVALENT).state)
    }

    @Test fun inProgressIncompleteAndFailedDoNotCountAsPassed() {
        listOf(CompletionStatus.IN_PROGRESS, CompletionStatus.INCOMPLETE, CompletionStatus.FAILED,
            CompletionStatus.NOT_TAKEN).forEach { status ->
            assertEquals(PrerequisiteState.NOT_MET, evaluate(a, "A" to status).state)
        }
    }

    @Test fun missingRecordIsUnknownInsteadOfAutomaticallyFailed() {
        assertEquals(PrerequisiteState.UNKNOWN, evaluate(a).state)
        assertEquals(PrerequisiteState.UNKNOWN, evaluate(a, "A" to CompletionStatus.UNKNOWN).state)
    }

    @Test fun unknownReferenceCannotBeSatisfiedByARecord() {
        val result = evaluate(Prerequisite.Course("MISSING"), "MISSING" to CompletionStatus.PASSED)
        assertEquals(PrerequisiteState.UNKNOWN, result.state)
        assertTrue(result.reasons.any { it.contains("MISSING") })
    }

    @Test fun andOrExpressionAcceptsPassedAlternative() {
        val expression = Prerequisite.AllOf(listOf(a, Prerequisite.AnyOf(listOf(b, c))))
        assertEquals(PrerequisiteState.MET, evaluate(expression, "A" to CompletionStatus.PASSED,
            "B" to CompletionStatus.FAILED, "C" to CompletionStatus.PASSED).state)
    }

    @Test fun andOrExpressionPreservesUnknownAlternative() {
        val expression = Prerequisite.AllOf(listOf(a, Prerequisite.AnyOf(listOf(b, c))))
        assertEquals(PrerequisiteState.UNKNOWN, evaluate(expression, "A" to CompletionStatus.PASSED,
            "B" to CompletionStatus.FAILED).state)
    }

    @Test fun falseDominatesAndEvenWhenOtherRecordIsUnknown() {
        assertEquals(PrerequisiteState.NOT_MET, evaluate(Prerequisite.AllOf(listOf(a, b)),
            "A" to CompletionStatus.FAILED).state)
    }

    @Test fun trueDominatesOrEvenWhenOtherRecordIsUnknown() {
        assertEquals(PrerequisiteState.MET, evaluate(Prerequisite.AnyOf(listOf(a, b)),
            "A" to CompletionStatus.PASSED).state)
    }

    @Test fun allFalseOrIsNotMetAndAllTrueAndIsMet() {
        val failed = arrayOf("A" to CompletionStatus.FAILED, "B" to CompletionStatus.FAILED)
        assertEquals(PrerequisiteState.NOT_MET, evaluate(Prerequisite.AnyOf(listOf(a, b)), *failed).state)
        assertEquals(PrerequisiteState.MET, evaluate(Prerequisite.AllOf(listOf(a, b)),
            "A" to CompletionStatus.PASSED, "B" to CompletionStatus.PASSED).state)
    }

    @Test fun documentedEmptyAndMeansNoPrerequisitesButEmptyOrIsUnknown() {
        assertEquals(PrerequisiteState.MET, evaluate(Prerequisite.AllOf(emptyList())).state)
        assertEquals(PrerequisiteState.UNKNOWN, evaluate(Prerequisite.AnyOf(emptyList())).state)
        assertEquals(PrerequisiteState.UNKNOWN, evaluate(Prerequisite.Unknown("No approved rule")).state)
    }

    @Test fun unmetReasonsIdentifyActualCourseAndStatus() {
        val result = evaluate(a, "A" to CompletionStatus.IN_PROGRESS)
        assertTrue(result.reasons.single().contains("A"))
        assertTrue(result.reasons.single().contains("IN_PROGRESS"))
    }
}
