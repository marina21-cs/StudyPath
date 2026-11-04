package com.studypath.app.data

import org.junit.Assert.*
import org.junit.Test

/** Review/source claims below are fabricated fixtures and must never enter production publication. */
class ReadingContextTest {
    private val preferences = LearnerPreferences("fixture_level", "fixture_v1", setOf("fixture_subject"), "fixture_class", true)
    private val options = LearnerPreferenceOptions("fixture_v1", "fixture_level", setOf("fixture_subject"), true, "APPROVED")
    private val review = mapOf("status" to "APPROVED", "reviewer" to "TEST ONLY", "reviewedOn" to "2026-10-26", "approvalReference" to "TEST ONLY")
    private fun moduleFields(verified: VerifiedTextPackage = TextPackageFixture.verified()): Map<String, Any?> = mapOf(
        "schemaVersion" to 1, "kind" to "MODULE_CONCEPT", "curriculumVersion" to "fixture_v1",
        "subjectId" to "fixture_subject", "query" to "Equal parts", "review" to review,
        "moduleId" to "fixture_module", "moduleVersion" to 1, "packageSha256" to verified.summary.packageSha256, "conceptId" to "parts")
    private fun weakFields(): Map<String, Any?> = mapOf("schemaVersion" to 1, "kind" to "WEAK_CATEGORY",
        "curriculumVersion" to "fixture_v1", "subjectId" to "fixture_subject", "query" to "Computer programming",
        "review" to review, "courseId" to "it101", "planVersion" to "reviewed_fixture_v1", "categoryId" to "quizzes")
    private fun course() = CatalogCourse("it101", "PRIVATE_NAME_NOT_A_QUERY", 3.0, "PRIVATE_TOPIC_NOT_A_QUERY", "fixture_module",
        true, "NONE", emptyList(), CatalogGradePlan("reviewed_fixture_v1", true, true, "TEST ONLY", listOf(
            CatalogCategory("quizzes", 1.0, "POOLED_POINTS", listOf(CatalogAssessment("q1", "PRIVATE_NOTE_NOT_A_QUERY", 10.0))))))
    private fun sources(verified: VerifiedTextPackage = TextPackageFixture.verified()) = ReadingContextSources(
        preferences, options, listOf(verified.summary), listOf(verified), listOf(course()))
    private fun resolve(fields: Map<String, Any?> = moduleFields(), source: ReadingContextSources = sources()): ResolvedReadingContext {
        val definition = ReadingContextRecords.definition("context_fixture", fields)
        return ReadingContextResolver.resolve(ReadingContext("context_fixture"), listOf(definition), source)
    }
    private fun rejected(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun companionReferenceContainsOnlyVersionAndCatalogId() {
        val reference = ReadingContext("context_fixture")
        val fields = ReadingContextRecords.reference(reference)
        assertEquals(mapOf("schemaVersion" to 1, "contextId" to "context_fixture"), fields)
        assertEquals(reference, ReadingContextRecords.reference(fields))
        listOf("query", "grade", "name", "notes", "token", "review", "moduleId").forEach { field ->
            rejected { ReadingContextRecords.reference(fields + (field to "private")) }
        }
    }

    @Test fun malformedReferenceVersionAndIdAreRejected() {
        listOf(null, "1", true, 2, 1.5).forEach { version ->
            rejected { ReadingContextRecords.reference(mapOf("schemaVersion" to version, "contextId" to "context_fixture")) }
        }
        listOf("", "bad/id", "x".repeat(65)).forEach { id -> rejected { ReadingContextRecords.reference(ReadingContext(id)) } }
        rejected { ReadingContextRecords.reference(ReadingContext("valid", 2)) }
    }

    @Test fun definitionRoundTripPreservesExactReviewedRegistryFields() {
        val fields = moduleFields()
        val parsed = ReadingContextRecords.definition("context_fixture", fields)
        assertEquals(ReadingContextKind.MODULE_CONCEPT, parsed.kind)
        assertEquals("parts", parsed.conceptId)
        assertEquals(fields, ReadingContextRecords.definition(parsed))
        rejected { ReadingContextRecords.definition("context_fixture", fields + ("grade" to 99)) }
        rejected { ReadingContextRecords.definition("context_fixture", fields - "conceptId") }
    }

    @Test fun weakCategoryDefinitionRejectsModuleFieldsAndUnknownKinds() {
        val fields = weakFields()
        val definition = ReadingContextRecords.definition("weak_fixture", fields)
        assertEquals(ReadingContextKind.WEAK_CATEGORY, definition.kind)
        assertEquals(fields, ReadingContextRecords.definition(definition))
        rejected { ReadingContextRecords.definition("weak_fixture", fields + ("conceptId" to "forged")) }
        rejected { ReadingContextRecords.definition("weak_fixture", fields + ("kind" to "OWNER_NOTES")) }
    }

    @Test fun queryBoundsUnicodeAndControlsAreValidated() {
        listOf("", " ", "x".repeat(121), "query\nprivate", "topic\u0000token", "\uD800").forEach { query ->
            rejected { ReadingContextRecords.definition("context_fixture", moduleFields() + ("query" to query)) }
        }
        assertEquals("Pag-aaral", resolve(moduleFields() + ("query" to "Pag-aaral")).query)
    }

    @Test fun incompleteReviewMalformedDateAndUnexpectedReviewFieldsAreRejected() {
        listOf(review - "approvalReference", review + ("reviewedOn" to "2026-02-30"), review + ("reviewer" to ""),
            review + ("approved" to true), review + ("status" to "OWNER_APPROVED")).forEach { invalid ->
            rejected { ReadingContextRecords.definition("context_fixture", moduleFields() + ("review" to invalid)) }
        }
    }

    @Test fun unapprovedOrWithdrawnDefinitionCannotGenerateAQuery() {
        listOf("REVIEW_PENDING", "DEMONSTRATION_ONLY", "WITHDRAWN").forEach { status ->
            rejected { resolve(moduleFields() + ("review" to (review + ("status" to status)))) }
        }
    }

    @Test fun unsupportedChangedOrIncompletePreferencesCannotGenerateAQuery() {
        val source = sources()
        listOf(source.copy(preferences = LearnerPreferences()), source.copy(options = null),
            source.copy(options = options.copy(approved = false)),
            source.copy(preferences = preferences.copy(curriculumVersion = "another_version")),
            source.copy(preferences = preferences.copy(subjectIds = setOf("another_subject"))),
            source.copy(preferences = preferences.copy(levelId = "another_level"))).forEach { invalid ->
            rejected { resolve(source = invalid) }
        }
    }

    @Test fun missingOrDuplicateRegistryIdentityFailsClosed() {
        val definition = ReadingContextRecords.definition("context_fixture", moduleFields())
        rejected { ReadingContextResolver.resolve(ReadingContext("missing"), listOf(definition), sources()) }
        rejected { ReadingContextResolver.resolve(ReadingContext("context_fixture"), listOf(definition, definition), sources()) }
    }

    @Test fun moduleQueryRequiresExactCurrentSummaryAndVerifiedPackageConcept() {
        val value = resolve()
        assertEquals("Equal parts", value.query)
        assertEquals(ReadingContext("context_fixture"), value.context)
        val source = sources()
        rejected { resolve(source = source.copy(modules = emptyList())) }
        rejected { resolve(source = source.copy(packages = emptyList())) }
        rejected { resolve(moduleFields() + ("conceptId" to "missing_concept")) }
        rejected { resolve(moduleFields() + ("moduleVersion" to 2)) }
        rejected { resolve(moduleFields() + ("packageSha256" to "a".repeat(64))) }
    }

    @Test fun withdrawnOrChangedModuleAndClassRestrictionFailClosed() {
        val source = sources()
        rejected { resolve(source = source.copy(modules = listOf(source.modules.single().copy(approved = false)))) }
        rejected { resolve(source = source.copy(modules = listOf(source.modules.single().copy(packageSha256 = "b".repeat(64))))) }
        rejected { resolve(source = source.copy(preferences = preferences.copy(classSectionId = "another_class"))) }
        rejected { resolve(source = source.copy(modules = source.modules + source.modules)) }
    }

    @Test fun retainedBytesAreReverifiedInsteadOfTrustingAPublicDataClass() {
        val source = sources()
        val forged = source.packages.single().copy(manifestJson = source.packages.single().manifestJson.replace("equal parts", "private payload"))
        rejected { resolve(source = source.copy(packages = listOf(forged))) }
    }

    @Test fun weakCategoryQueryUsesReviewedBindingWithoutPrivateCourseText() {
        val resolved = resolve(weakFields())
        assertEquals("Computer programming", resolved.query)
        assertFalse(resolved.query.contains("PRIVATE"))
        assertEquals("quizzes", resolved.definition.categoryId)
        assertNull(resolved.definition.moduleId)
    }

    @Test fun unknownUnverifiedOrStalePlanCategoryCannotGenerateAQuery() {
        val source = sources()
        val course = course()
        rejected { resolve(weakFields(), source.copy(courses = emptyList())) }
        rejected { resolve(weakFields(), source.copy(courses = listOf(course.copy(gradePlan = null)))) }
        rejected { resolve(weakFields(), source.copy(courses = listOf(course.copy(gradePlan = course.gradePlan!!.copy(verified = false))))) }
        rejected { resolve(weakFields() + ("planVersion" to "stale")) }
        rejected { resolve(weakFields() + ("categoryId" to "missing")) }
        rejected { resolve(weakFields(), source.copy(courses = listOf(course, course))) }
    }

    @Test fun definitionObjectsCannotBypassSchemaValidationAfterParsing() {
        val definition = ReadingContextRecords.definition("context_fixture", moduleFields())
        rejected { ReadingContextResolver.resolve(ReadingContext("context_fixture"), listOf(definition.copy(query = "x".repeat(121))), sources()) }
        rejected { ReadingContextResolver.resolve(ReadingContext("context_fixture"), listOf(definition.copy(courseId = "mixed_kind")), sources()) }
    }
}
