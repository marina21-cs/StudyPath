package com.studypath.app.data

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class TextPackageVerifierTest {
    @Test fun reviewedAssetPolicyAndCompletePdfInventoryCanBeVerifiedWithoutClaimingLocalReadiness() {
        val (summary, manifest, text) = CompletePackageFixture.candidate()
        val verification = runCatching { TextPackageVerifier.verify(summary, manifest, text) }
        assertTrue("Reviewed complete asset inventories should be accepted for staged byte validation: ${verification.exceptionOrNull()}", verification.isSuccess)
        val verified = verification.getOrThrow()
        assertEquals(summary.packageSha256, verified.summary.packageSha256)
        assertEquals(CompletePackageFixture.pdf.size.toLong(), verified.summary.assetBytes)
    }

    @Test fun assetApprovalOriginsAndBudgetsAreBoundToTheImmutableManifest() {
        val mutations: List<(com.google.gson.JsonObject) -> Unit> = listOf(
            { it.remove("assetPolicy") },
            { it.getAsJsonObject("assetPolicy").addProperty("approvalReference", "different approval") },
            { it.getAsJsonObject("assetPolicy").addProperty("access", "UPLOAD") },
            { it.getAsJsonObject("assetPolicy").addProperty("maxFileBytes", 1) },
            { it.getAsJsonObject("assetPolicy").addProperty("maxTotalBytes", 1) },
            { it.getAsJsonObject("assetPolicy").addProperty("maxCount", 21) },
            { it.getAsJsonObject("assetPolicy").addProperty("reviewedOn", "2026-02-30") },
            { it.getAsJsonArray("assets")[0].asJsonObject.addProperty("url", "http://assets.example.org/source.pdf") },
            { it.getAsJsonArray("assets")[0].asJsonObject.addProperty("url", "https://foreign.example.org/source.pdf") },
            { it.getAsJsonArray("assets")[0].asJsonObject.addProperty("url", "https://assets.example.org/source.pdf#fragment") },
            { it.getAsJsonArray("assets")[0].asJsonObject.addProperty("file", "../source.pdf") },
            { it.getAsJsonObject("assetPolicy").add("origins", com.google.gson.JsonArray().apply { add("https://127.0.0.1") }) },
        )
        mutations.forEach { mutate ->
            val (summary, manifest, text) = CompletePackageFixture.candidate(change = mutate)
            assertThrows(IllegalArgumentException::class.java) { TextPackageVerifier.verify(summary, manifest, text) }
        }
        val (summary, manifest, text) = CompletePackageFixture.candidate()
        assertThrows(IllegalArgumentException::class.java) { TextPackageVerifier.verify(summary.copy(assetBytes = summary.assetBytes + 1), manifest, text) }
    }

    @Test fun rawB09MaintainerBytesAndPackageHashAreCompatible() {
        val summary = TextPackageFixture.summary
        val result = TextPackageVerifier.verify(summary, TextPackageFixture.manifestJson, TextPackageFixture.textJson)
        assertEquals(summary, result.summary)
        assertEquals(TextPackageFixture.manifestJson, result.manifestJson)
        assertEquals(TextPackageFixture.textJson, result.textJson)
        assertEquals("fixture_module", result.payload.get("moduleId").asString)
        assertEquals(TextPackageKey("fixture_v1", "fixture_module", 1), result.key)
    }

    @Test fun summaryIdentityApplicabilityAndApprovalMustMatchTheManifestExactly() {
        val summary = TextPackageFixture.summary
        val variants = listOf(summary.copy(moduleId = "other"), summary.copy(version = 2), summary.copy(title = "Other"),
            summary.copy(curriculumVersion = "other"), summary.copy(levelId = "other"), summary.copy(subjectId = "other"),
            summary.copy(classIds = emptySet()), summary.copy(approved = false), summary.copy(source = summary.source + ("author" to "Other")),
            summary.copy(review = summary.review + ("reviewer" to "Other")))
        variants.forEach { bad -> rejected(bad) }
    }

    @Test fun pendingReviewOrMissingRedistributionPermissionNeverBecomesVerified() {
        for ((key, value) in listOf("status" to "REVIEW_PENDING", "redistributionAllowed" to false)) {
            val summary = TextPackageFixture.summary
            val manifest = JsonParser.parseString(TextPackageFixture.manifestJson).asJsonObject.apply {
                if (value is String) getAsJsonObject("review").addProperty(key, value)
                else getAsJsonObject("review").addProperty(key, value as Boolean)
            }.toString()
            rejected(TextPackageFixture.bind(summary.copy(review = summary.review + (key to value)), manifest, TextPackageFixture.textJson), manifest)
        }
    }

    @Test fun rawTextLengthDigestAndCombinedSummarySizeAreVerified() {
        val summary = TextPackageFixture.summary
        rejected(summary.copy(textBytes = summary.textBytes + 1))
        rejected(summary.copy(packageSha256 = "0".repeat(64)))
        rejected(summary, text = TextPackageFixture.textJson + " ")
        for ((key, value) in listOf("bytes" to "1", "sha256" to "0".repeat(64))) {
            val manifest = JsonParser.parseString(TextPackageFixture.manifestJson).asJsonObject.apply {
                if (key == "bytes") getAsJsonObject("text").addProperty(key, value.toInt())
                else getAsJsonObject("text").addProperty(key, value)
            }.toString()
            rejected(TextPackageFixture.bind(summary, manifest, TextPackageFixture.textJson), manifest)
        }
    }

    @Test fun utf8ByteCapCountsMultibyteCharactersAndRejectsExactly100000Bytes() {
        val under = TextPackageFixture.pair(teaching = "é".repeat(40_000))
        assertTrue(TextPackageVerifier.verify(under.first, under.second.first, under.second.second).summary.textBytes < 100_000)
        val over = TextPackageFixture.pair(teaching = "é".repeat(50_000))
        rejected(over.first, over.second.first, over.second.second)
        var padding = 98_000
        var exact = TextPackageFixture.pair(teaching = "a".repeat(padding))
        repeat(4) {
            padding += 100_000 - exact.first.textBytes.toInt()
            exact = TextPackageFixture.pair(teaching = "a".repeat(padding))
        }
        assertEquals(100_000L, exact.first.textBytes)
        rejected(exact.first, exact.second.first, exact.second.second)
    }

    @Test fun malformedJsonDuplicateFieldsAndUnpairedUnicodeFailClosed() {
        rejected(TextPackageFixture.summary, manifest = "{")
        rejected(TextPackageFixture.summary, text = "[]")
        val duplicate = TextPackageFixture.textJson.replaceFirst("{", "{\"moduleId\":\"foreign\",")
        rejectReboundText(duplicate)
        rejectReboundText("{\"moduleId\":\"fixture_module\",\"version\":1,\"text\":\"\\uD800\"}")
        rejectReboundText("{moduleId:'fixture_module',version:1}")
        rejectReboundText(TextPackageFixture.textJson + " trailing")
    }

    @Test fun rawPayloadIdentityAndAssetBoundariesAreEnforced() {
        for (text in listOf("{\"moduleId\":\"foreign\",\"version\":1}", "{\"moduleId\":\"fixture_module\",\"version\":2}",
            "{\"moduleId\":\"fixture_module\",\"version\":\"1\"}")) rejectReboundText(text)
        val summary = TextPackageFixture.summary
        rejected(summary.copy(assetBytes = 1))
        val manifest = JsonParser.parseString(TextPackageFixture.manifestJson).asJsonObject.apply {
            getAsJsonArray("assets").add(com.google.gson.JsonObject())
        }.toString()
        rejected(TextPackageFixture.bind(summary, manifest, TextPackageFixture.textJson), manifest)
    }

    private fun rejectReboundText(text: String) {
        val manifest = JsonParser.parseString(TextPackageFixture.manifestJson).asJsonObject.apply {
            getAsJsonObject("text").apply {
                addProperty("bytes", text.toByteArray(Charsets.UTF_8).size); addProperty("sha256", TextPackageFixture.sha(text))
            }
        }.toString()
        rejected(TextPackageFixture.bind(TextPackageFixture.summary, manifest, text), manifest, text)
    }
    private fun rejected(summary: PublishedModuleSummary, manifest: String = TextPackageFixture.manifestJson, text: String = TextPackageFixture.textJson) {
        assertThrows(IllegalArgumentException::class.java) { TextPackageVerifier.verify(summary, manifest, text) }
    }
}
