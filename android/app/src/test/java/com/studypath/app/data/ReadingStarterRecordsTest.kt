package com.studypath.app.data

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ReadingStarterRecordsTest {
    private fun fixture(): Map<String, Map<String, Any?>> {
        val json = requireNotNull(javaClass.getResourceAsStream("/reading-starters.json")).bufferedReader().use { it.readText() }
        @Suppress("UNCHECKED_CAST") return Gson().fromJson(json, Map::class.java) as Map<String, Map<String, Any?>>
    }
    private fun example() = fixture().entries.first()
    private fun rejected(change: Map<String, Any?>) {
        val row = example()
        assertThrows(IllegalArgumentException::class.java) { ReadingStarterRecords.parse(row.key, row.value + change) }
    }
    @Test fun gatheredApiRecordsPreserveExactSourceRevisionAndLicense() {
        val rows = fixture().map { (key, value) -> ReadingStarterRecords.parse(key, value) }
        assertEquals(setOf("Computer_programming", "Data_structure", "Relational_database"), rows.map { it.pageKey }.toSet())
        rows.forEach { row ->
            assertEquals("wiki_${row.pageId}_${row.revisionId}", row.id)
            assertEquals("https://creativecommons.org/licenses/by-sa/4.0/deed.en", row.licenseUrl)
            assertEquals("https://en.wikipedia.org/w/index.php?oldid=${row.revisionId}", row.revisionUrl)
            assertTrue(row.description.isNotBlank() && row.attribution.startsWith("Wikipedia contributors."))
        }
    }
    @Test fun unsupportedSchemasAndApprovalClaimsAreRejected() {
        rejected(mapOf("schemaVersion" to 2)); rejected(mapOf("approved" to true)); rejected(mapOf("kind" to "APPROVED_LESSON"))
        rejected(mapOf("provider" to "OTHER")); rejected(mapOf("schemaVersion" to "1"))
    }
    @Test fun missingAndUnknownFieldsFailClosed() {
        val row = example()
        for (field in row.value.keys) assertThrows(IllegalArgumentException::class.java) { ReadingStarterRecords.parse(row.key, row.value - field) }
        rejected(mapOf("html" to "<script>bad</script>"))
    }
    @Test fun pathIdentityCannotPointToAnotherPageOrRevision() {
        val row = example()
        for (key in listOf("other", "wiki_1_1", "bad/path"))
            assertThrows(IllegalArgumentException::class.java) { ReadingStarterRecords.parse(key, row.value) }
    }
    @Test fun numericTypesAndBoundsAreStrict() {
        for (field in listOf("pageId", "revisionId")) for (value in listOf(0, -1, 1.5, "12", Double.NaN, 9_007_199_254_740_992.0)) rejected(mapOf(field to value))
        for (value in listOf(-1, 4_102_444_800_001L, 1.5, "42")) rejected(mapOf("retrievedAt" to value))
    }
    @Test fun hostileOrMismatchedSourceAndLicenseUrlsAreRejected() {
        for (value in listOf("http://en.wikipedia.org/wiki/test", "https://evil.example/wiki/test", "javascript:alert(1)", "https://en.wikipedia.org/wiki/Wrong")) rejected(mapOf("sourceUrl" to value))
        for (value in listOf("https://en.wikipedia.org/w/index.php?oldid=1", "https://en.wikipedia.org.evil/w/index.php?oldid=1")) rejected(mapOf("revisionUrl" to value))
        for (value in listOf("http://creativecommons.org/licenses/by-sa/4.0/deed.en", "https://creativecommons.org/licenses/by-sa/4.0/?unsafe=1", "https://creativecommons.org/licenses/by/4.0/")) rejected(mapOf("licenseUrl" to value))
    }
    @Test fun timestampsAndBoundedTextAreValidated() {
        for (value in listOf("2026-02-30T00:00:00Z", "2026-10-26", "2026-10-26T00:00:00+00:00")) rejected(mapOf("revisionTimestamp" to value))
        for ((field, max) in mapOf("title" to 240, "pageKey" to 240, "description" to 500, "topicQuery" to 120, "licenseTitle" to 80, "attribution" to 500)) {
            rejected(mapOf(field to " ")); rejected(mapOf(field to "x".repeat(max + 1)))
        }
    }
    @Test fun unicodeAndPunctuationUseTheSameCanonicalUrlAsSavedReadings() {
        val row = example()
        val value = row.value + mapOf("pageKey" to "Café !'()~*", "sourceUrl" to "https://en.wikipedia.org/wiki/Caf%C3%A9%20%21%27%28%29%7E*")
        assertEquals("Café !'()~*", ReadingStarterRecords.parse(row.key, value).pageKey)
        assertThrows(IllegalArgumentException::class.java) {
            ReadingStarterRecords.parse(row.key, value + ("sourceUrl" to "https://en.wikipedia.org/wiki/Caf%C3%A9%20!'()~*"))
        }
        rejected(mapOf("pageKey" to "\uD800"))
    }
}
