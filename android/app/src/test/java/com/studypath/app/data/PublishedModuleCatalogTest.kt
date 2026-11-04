package com.studypath.app.data

import org.junit.Assert.*
import org.junit.Test
import com.google.gson.Gson

class PublishedModuleCatalogTest {
    private val preferences = LearnerPreferences("fixture_level", "fixture_v1", setOf("fixture_subject"), "0", true)
    private val options = LearnerPreferenceOptions("fixture_v1", "fixture_level", setOf("fixture_subject"), true, "APPROVED")
    private fun record(): Map<String, Any?> = mapOf(
        "schemaVersion" to 1L, "moduleId" to "fixture_module", "version" to 1L,
        "title" to "Synthetic test fixture", "visibility" to "PUBLIC", "textBytes" to 2400L, "assetBytes" to 0L,
        "packageSha256" to "a".repeat(64),
        "applicability" to mapOf("curriculumVersion" to "fixture_v1", "levelId" to "fixture_level", "subjectId" to "fixture_subject", "classIds" to mapOf("0" to true)),
        "source" to mapOf("title" to "Synthetic", "reference" to "https://example.invalid/test", "locator" to "Fixture", "author" to "TEST ONLY", "version" to "fixture_v1"),
        "review" to mapOf("status" to "APPROVED", "reviewer" to "TEST ONLY", "reviewedOn" to "2026-10-26", "approvalReference" to "TEST ONLY", "permissionReference" to "TEST ONLY", "adaptationAllowed" to true, "redistributionAllowed" to true),
    )
    private fun parse(value: Map<String, Any?> = record(), curriculum: String = "fixture_v1", module: String = "fixture_module", version: String = "v1") =
        PublishedModuleCatalog.parse(curriculum, module, version, value)
    private fun rejected(value: Map<String, Any?>) { assertThrows(IllegalArgumentException::class.java) { parse(value) } }

    @Test fun exactImmutableIdentityAndProvenanceSurviveParsing() {
        val value = parse()
        assertEquals("fixture_module", value.moduleId); assertEquals(1, value.version)
        assertEquals(setOf("0"), value.classIds); assertEquals("a".repeat(64), value.packageSha256)
        assertEquals("TEST ONLY", value.source["author"]); assertEquals("TEST ONLY", value.review["reviewer"])
        assertTrue(value.approved); assertEquals(2400L, value.textBytes)
    }
    @Test fun immutablePathMustMatchRecordIdentity() {
        for ((curriculum, module, version) in listOf(Triple("wrong_v1", "fixture_module", "v1"), Triple("fixture_v1", "wrong", "v1"),
            Triple("fixture_v1", "fixture_module", "v2"), Triple("fixture_v1", "fixture_module", "v01"))) {
            assertThrows(IllegalArgumentException::class.java) { parse(curriculum = curriculum, module = module, version = version) }
        }
    }
    @Test fun unknownFieldsVersionsAndRestrictedVisibilityFailClosed() {
        rejected(record() + ("unknown" to true)); rejected(record() + ("schemaVersion" to 2)); rejected(record() + ("visibility" to "CLASS_PRIVATE"))
        rejected(record() - "source"); rejected(record() + ("title" to " "))
    }
    @Test fun malformedSizesHashesAndNumericCoercionsAreRejected() {
        for (bytes in listOf(-1, 0, 100000, 1.5, "100", Double.NaN)) rejected(record() + ("textBytes" to bytes))
        for (version in listOf(0, 1.5, 1000001, "1")) rejected(record() + ("version" to version))
        rejected(record() + ("assetBytes" to -1)); rejected(record() + ("packageSha256" to "A".repeat(64)))
    }
    @Test fun applicabilityRejectsUnknownFieldsInvalidIdsAndNonBooleanClassMaps() {
        @Suppress("UNCHECKED_CAST") val app = record()["applicability"] as Map<String, Any?>
        for (value in listOf(app + ("role" to "teacher"), app + ("subjectId" to "bad id"), app + ("classIds" to listOf("0")),
            app + ("classIds" to mapOf("0" to 1)))) rejected(record() + ("applicability" to value))
        assertTrue(parse(record() + ("applicability" to (app - "classIds"))).classIds.isEmpty())
    }
    @Test fun reviewDatePermissionAndSourceHaveStrictTypes() {
        @Suppress("UNCHECKED_CAST") val review = record()["review"] as Map<String, Any?>
        for (value in listOf(review + ("reviewedOn" to "2026-02-30"), review + ("redistributionAllowed" to "true"), review - "permissionReference", review + ("unknown" to true)))
            rejected(record() + ("review" to value))
        rejected(record() + ("source" to mapOf("title" to "incomplete")))
        assertFalse(parse(record() + ("review" to (review + ("redistributionAllowed" to false)))).approved)
    }
    @Test fun incompleteAndStaleSelectionsHaveExplicitEmptyStates() {
        val module = parse()
        assertEquals(ModuleLibraryStatus.INCOMPLETE_SELECTION, PublishedModuleCatalog.select(LearnerPreferences(), options, listOf(module)).status)
        for (value in listOf(null, options.copy(approved = false), options.copy(approvalStatus = "DEMONSTRATION_ONLY"), options.copy(levelId = "other"))) {
            val result = PublishedModuleCatalog.select(preferences, value, listOf(module))
            assertEquals(ModuleLibraryStatus.UNSUPPORTED_SELECTION, result.status); assertTrue(result.modules.isEmpty())
        }
    }
    @Test fun onlyMatchingApprovedPublicApplicabilityIsDiscoverable() {
        val module = parse()
        val variants = listOf(module, module.copy(moduleId = "wrong_level", levelId = "other"), module.copy(moduleId = "wrong_subject", subjectId = "other"),
            module.copy(moduleId = "wrong_curriculum", curriculumVersion = "other"), module.copy(moduleId = "wrong_class", classIds = setOf("other")),
            module.copy(moduleId = "unreviewed", approved = false), module.copy(moduleId = "all_classes", classIds = emptySet()))
        val result = PublishedModuleCatalog.select(preferences, options, variants)
        assertEquals(ModuleLibraryStatus.AVAILABLE, result.status)
        assertEquals(setOf("fixture_module", "all_classes"), result.modules.map { it.moduleId }.toSet())
        assertEquals(listOf("all_classes"), PublishedModuleCatalog.select(preferences.copy(classSectionId = null), options, variants).modules.map { it.moduleId })
    }
    @Test fun matchingVersionsRemainDistinctAndEmptyLibraryIsExplicit() {
        val module = parse()
        assertEquals(listOf(2, 1), PublishedModuleCatalog.select(preferences, options, listOf(module, module.copy(version = 2))).modules.map { it.version })
        assertEquals(ModuleLibraryStatus.EMPTY, PublishedModuleCatalog.select(preferences, options, emptyList()).status)
    }
    @Test fun maintainerProjectionFixtureMatchesNativeConsumer() {
        val json = requireNotNull(javaClass.getResourceAsStream("/module-index.json")).bufferedReader().use { it.readText() }
        @Suppress("UNCHECKED_CAST") val fixture = Gson().fromJson(json, Map::class.java) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val catalog = fixture["catalog"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val index = (catalog["moduleIndex"] as Map<String, Any?>)["fixture_v1"] as Map<String, Map<String, Map<String, Any?>>>
        val modules = index.flatMap { (module, versions) -> versions.map { (version, fields) -> parse(fields, module = module, version = version) } }
        assertEquals(listOf("all_classes:v1", "fixture_module:v2", "fixture_module:v1"),
            PublishedModuleCatalog.select(preferences, options, modules).modules.map { "${it.moduleId}:v${it.version}" })
    }
}
