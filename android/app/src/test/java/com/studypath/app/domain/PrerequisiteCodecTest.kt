package com.studypath.app.domain

import org.junit.Assert.*
import org.junit.Test

class PrerequisiteCodecTest {
    private fun document(node: Any?, version: Any? = 1): Map<String, Any?> =
        mapOf("schemaVersion" to version, "expression" to node)
    private fun ref(id: String): Map<String, Any> = mapOf("kind" to "COURSE", "courseId" to id)
    private fun group(kind: String, vararg nodes: Any?): Map<String, Any?> =
        mapOf("kind" to kind, "children" to nodes.toList())
    private val none = mapOf("kind" to "NONE")
    private fun failure(value: Any?, text: String) {
        val error = assertThrows(IllegalArgumentException::class.java) { PrerequisiteCodec.decode(value) }
        assertTrue("Expected '$text' in '${error.message}'", error.message.orEmpty().contains(text, ignoreCase = true))
    }

    @Test fun orderedMapAndSdkListDecodeToTheSameNestedExpression() {
        val list = group("AND", ref("A"), group("OR", ref("B"), ref("C")))
        val map = mapOf("kind" to "AND", "children" to linkedMapOf(
            "1" to mapOf("kind" to "OR", "children" to mapOf("1" to ref("C"), "0" to ref("B"))),
            "0" to ref("A")))
        assertEquals(Prerequisite.AllOf(listOf(Prerequisite.Course("A"),
            Prerequisite.AnyOf(listOf(Prerequisite.Course("B"), Prerequisite.Course("C"))))),
            PrerequisiteCodec.decode(document(list)))
        assertEquals(PrerequisiteCodec.decode(document(list)), PrerequisiteCodec.decode(document(map)))
    }

    @Test fun encodedNodesUseContiguousMapKeysAndRoundTripWithoutChangingOrder() {
        val expression = PrerequisiteCodec.decode(document(group("OR", ref("B"), ref("A"))))
        val encoded = PrerequisiteCodec.encode(expression)
        val node = encoded["expression"] as Map<*, *>
        assertEquals(listOf("0", "1"), (node["children"] as Map<*, *>).keys.toList())
        assertEquals(expression, PrerequisiteCodec.decode(encoded))
    }

    @Test fun explicitNoneIsMetButExplicitUnknownRetainsItsReason() {
        val noRequirement = PrerequisiteCodec.decode(document(none))
        val unknown = PrerequisiteCodec.decode(document(mapOf("kind" to "UNKNOWN", "reason" to "Syllabus review pending")))
        assertEquals(PrerequisiteState.MET, PrerequisiteEvaluator.evaluate(noRequirement, emptySet(), emptyMap()).state)
        val result = PrerequisiteEvaluator.evaluate(unknown, emptySet(), emptyMap())
        assertEquals(PrerequisiteState.UNKNOWN, result.state)
        assertEquals(listOf("Syllabus review pending"), result.reasons)
        assertEquals(none, PrerequisiteCodec.encode(noRequirement)["expression"])
    }

    @Test fun unknownVersionAndWrongVersionTypesAreRejected() {
        listOf(null, "1", true, 1.5, 2, Double.NaN).forEach { failure(document(none, it), "schemaVersion") }
        assertEquals(Prerequisite.AllOf(emptyList()), PrerequisiteCodec.decode(document(none, 1.0)))
    }

    @Test fun rootAndExpressionRequireExactFieldsAndObjects() {
        failure(document(none) + ("approved" to true), "fields")
        failure(mapOf("schemaVersion" to 1), "fields")
        failure(document(listOf(none)), "object")
        failure(document(none + ("children" to emptyList<Any>())), "fields")
        failure(document(ref("A") + ("reason" to "forged")), "fields")
        failure(document(mapOf("kind" to "MAYBE")), "kind")
    }

    @Test fun referenceIdsAndUnknownReasonsAreValidated() {
        listOf("", "bad id", "x".repeat(65)).forEach { failure(document(ref(it)), "courseId") }
        failure(document(mapOf("kind" to "COURSE", "courseId" to 3)), "courseId")
        listOf("", " ", "x".repeat(241)).forEach {
            failure(document(mapOf("kind" to "UNKNOWN", "reason" to it)), "reason")
        }
        failure(document(mapOf("kind" to "UNKNOWN")), "fields")
    }

    @Test fun emptyAndSparseOrNoncanonicalChildMapsAreRejected() {
        failure(document(group("AND")), "children")
        failure(document(group("OR")), "children")
        listOf(mapOf("1" to ref("A")), mapOf("00" to ref("A")), mapOf(0 to ref("A")),
            mapOf("0" to ref("A"), "2" to ref("B"))).forEach {
            failure(document(mapOf("kind" to "AND", "children" to it)), "contiguous")
        }
        failure(document(group("AND", null)), "object")
        failure(document(mapOf("kind" to "AND", "children" to true)), "children")
    }

    @Test fun duplicateReferencesAcrossSeparateBranchesAreRejected() {
        failure(document(group("AND", ref("A"), group("OR", ref("B"), ref("A")))), "duplicate")
    }

    @Test fun childDepthNodeAndReferenceBoundsRejectOversizedExpressions() {
        failure(document(group("AND", *Array(17) { none })), "children")
        var deep: Any? = ref("A")
        repeat(8) { deep = group("AND", deep) }
        failure(document(deep), "depth")
        val branch = group("AND", *Array(16) { none })
        failure(document(group("AND", branch, branch, branch, branch)), "nodes")
        val refs = (0 until 33).map { ref("C$it") }
        failure(document(group("AND", group("OR", *refs.take(16).toTypedArray()),
            group("OR", *refs.drop(16).take(16).toTypedArray()), refs.last())), "references")
    }

    @Test fun safeParsingPreservesInvalidStateWithoutLegacyFallback() {
        val invalid = PrerequisiteCodec.parse(document(group("AND")))
        assertNotNull(invalid.invalidReason)
        assertTrue(invalid.expression is Prerequisite.Unknown)
        assertTrue(invalid.invalidReason!!.contains("children"))
    }

    @Test fun catalogRejectsMissingReferencesDuplicateCourseIdsAndDirectForgedDuplicates() {
        val a = PrerequisiteDocument(Prerequisite.Course("MISSING"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            PrerequisiteCodec.validateCatalog(listOf("A" to a))
        }.message!!.contains("MISSING"))
        val empty = PrerequisiteDocument(Prerequisite.AllOf(emptyList()))
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            PrerequisiteCodec.validateCatalog(listOf("A" to empty, "A" to empty))
        }.message!!.contains("Duplicate"))
        assertThrows(IllegalArgumentException::class.java) {
            PrerequisiteCodec.validateCatalog(listOf("A" to PrerequisiteDocument(
                Prerequisite.AnyOf(listOf(Prerequisite.Course("B"), Prerequisite.Course("B")))), "B" to empty))
        }
    }

    @Test fun catalogDetectsSelfAndIndirectCyclesWithStableReasons() {
        assertEquals("Prerequisite cycle: A -> A", assertThrows(IllegalArgumentException::class.java) {
            PrerequisiteCodec.validateCatalog(listOf("A" to PrerequisiteDocument(Prerequisite.Course("A"))))
        }.message)
        val rows = listOf("B" to PrerequisiteDocument(Prerequisite.Course("C")),
            "C" to PrerequisiteDocument(Prerequisite.Course("A")),
            "A" to PrerequisiteDocument(Prerequisite.Course("B")))
        val expected = "Prerequisite cycle: A -> B -> C -> A"
        assertEquals(expected, assertThrows(IllegalArgumentException::class.java) {
            PrerequisiteCodec.validateCatalog(rows)
        }.message)
        assertEquals(expected, assertThrows(IllegalArgumentException::class.java) {
            PrerequisiteCodec.validateCatalog(rows.reversed())
        }.message)
    }

    @Test fun catalogRejectsInvalidDocumentsAndExcessiveCourseCounts() {
        assertThrows(IllegalArgumentException::class.java) {
            PrerequisiteCodec.validateCatalog(listOf("A" to PrerequisiteCodec.parse(document(group("OR")))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrerequisiteCodec.validateCatalog((0..200).map {
                "C$it" to PrerequisiteDocument(Prerequisite.AllOf(emptyList()))
            })
        }
    }

    @Test fun unknownAndExplicitNoneRemainDifferentAfterSerialization() {
        val unknown = Prerequisite.Unknown("No reviewed policy")
        assertEquals(unknown, PrerequisiteCodec.decode(PrerequisiteCodec.encode(unknown)))
        assertNotEquals(PrerequisiteCodec.encode(unknown), PrerequisiteCodec.encode(Prerequisite.AllOf(emptyList())))
    }
}
