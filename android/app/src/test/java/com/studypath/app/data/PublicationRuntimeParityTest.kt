package com.studypath.app.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

/** The same raw fixtures gate maintainer publication in Node and native lesson parsing here. */
class PublicationRuntimeParityTest {
    @Test fun sharedPublicationCorpusMatchesNativeParser() {
        val raw = requireNotNull(javaClass.getResourceAsStream("/interactive-runtime-contract.json"))
            .bufferedReader().use { it.readText() }
        val corpus = JsonParser.parseString(raw).asJsonObject.getAsJsonArray("cases")
        corpus.forEach { value ->
            val row = value.asJsonObject
            @Suppress("UNCHECKED_CAST")
            val fields = Gson().fromJson(row["summary"], Map::class.java) as Map<String, Any?>
            val accepted = runCatching {
                val summary = PublishedModuleCatalog.parse("fixture_v1", "fixture_module", "v1", fields)
                val verified = TextPackageVerifier.verify(summary, row["manifestJson"].asString, row["textJson"].asString)
                InteractiveLessonParser.parse(verified)
            }.isSuccess
            assertEquals(row["id"].asString, row["accepted"].asBoolean, accepted)
        }
    }
}
