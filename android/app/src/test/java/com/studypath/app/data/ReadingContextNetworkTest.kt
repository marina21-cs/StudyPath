package com.studypath.app.data

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Local synthetic server fixtures only; these review claims are not content approvals. */
class ReadingContextNetworkTest {
    private val gson = Gson()
    private fun definition(module: VerifiedTextPackage? = null): Map<String, Any> = buildMap {
        put("schemaVersion", 1); put("kind", if (module == null) "WEAK_CATEGORY" else "MODULE_CONCEPT")
        put("curriculumVersion", "fixture_v1"); put("subjectId", "fixture_subject"); put("query", "Computer programming")
        put("review", mapOf("status" to "APPROVED", "reviewer" to "TEST ONLY", "reviewedOn" to "2026-10-26", "approvalReference" to "TEST ONLY"))
        if (module == null) { put("courseId", "it101"); put("planVersion", "fixture_plan"); put("categoryId", "quiz") }
        else { put("moduleId", module.summary.moduleId); put("moduleVersion", module.summary.version); put("packageSha256", module.summary.packageSha256); put("conceptId", "parts") }
    }
    private val preferences = mapOf("schemaVersion" to 1, "onboardingComplete" to true, "curriculumVersion" to "fixture_v1",
        "levelId" to "fixture_level", "subjectIds" to mapOf("fixture_subject" to true), "classSectionId" to "fixture_class")
    private val options = mapOf("schemaVersion" to 1, "curriculumVersion" to "fixture_v1", "levelId" to "fixture_level",
        "subjectIds" to mapOf("fixture_subject" to true), "approved" to true, "approvalStatus" to "APPROVED")
    private val plan = mapOf("version" to "fixture_plan", "verified" to true, "assessmentPlanKnown" to true,
        "label" to "TEST ONLY", "categories" to mapOf("quiz" to mapOf("weight" to 1.0, "aggregation" to "POOLED_POINTS",
            "assessments" to mapOf("q1" to mapOf("title" to "Fixture", "possiblePoints" to 10.0)))))
    private fun enqueue(server: MockWebServer, value: Any) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(gson.toJson(value))) }
    private fun network(server: MockWebServer) = ReadingContextNetwork(OkHttpClient(), server.url("/?ns=fixture-namespace"))

    @Test fun freshWeakContextReadsOnlyRequiredPathsAndKeepsAuthenticationOffQueryText() = runBlocking {
        MockWebServer().use { server ->
            enqueue(server, definition()); enqueue(server, preferences); enqueue(server, options); enqueue(server, plan)
            val result = network(server).resolve(ReadingContext("context_fixture"), "owner_uid", "synthetic.id.token")
            assertEquals("Computer programming", result.query)
            val expected = listOf("/catalog/readingContexts/context_fixture.json", "/users/owner_uid/profile/learnerPreferences.json",
                "/catalog/learnerPreferenceOptions/fixture_v1.json", "/catalog/courses/it101/gradePlan.json")
            expected.forEach { path ->
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("GET", request.method); assertEquals(path, request.requestUrl!!.encodedPath)
                assertEquals(setOf("ns", "auth"), request.requestUrl!!.queryParameterNames)
                assertEquals("synthetic.id.token", request.requestUrl!!.queryParameter("auth"))
                assertEquals("fixture-namespace", request.requestUrl!!.queryParameter("ns"))
                assertEquals(0L, request.bodySize)
            }
            assertFalse(result.query.contains("owner_uid")); assertFalse(result.query.contains("token"))
        }
    }

    @Test fun moduleQueryRequiresFreshExactIndexAndVerifiedRetainedPackage() = runBlocking {
        MockWebServer().use { server ->
            val module = TextPackageFixture.verified()
            enqueue(server, definition(module)); enqueue(server, preferences); enqueue(server, options)
            val s = module.summary
            enqueue(server, mapOf("schemaVersion" to 1, "moduleId" to s.moduleId, "version" to s.version, "title" to s.title,
                "visibility" to "PUBLIC", "applicability" to mapOf("curriculumVersion" to s.curriculumVersion, "levelId" to s.levelId,
                    "subjectId" to s.subjectId, "classIds" to s.classIds.associateWith { true }),
                "source" to s.source, "review" to s.review, "textBytes" to s.textBytes, "assetBytes" to s.assetBytes, "packageSha256" to s.packageSha256))
            assertEquals("Computer programming", network(server).resolve(ReadingContext("module_fixture"), "owner_uid", "synthetic.token", listOf(module)).query)
            repeat(3) { server.takeRequest() }
            assertEquals("/catalog/moduleIndex/fixture_v1/fixture_module/v1.json", server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    @Test fun freshFailureCannotFallBackToAnOldSuccessfulContext() = runBlocking {
        MockWebServer().use { server ->
            enqueue(server, definition()); enqueue(server, preferences); enqueue(server, options); enqueue(server, plan)
            val network = network(server)
            assertEquals("Computer programming", network.resolve(ReadingContext("context_fixture"), "owner_uid", "synthetic.token").query)
            server.enqueue(MockResponse().setResponseCode(503).setBody("synthetic.token https://private.invalid/secret"))
            val failure = runCatching { network.resolve(ReadingContext("context_fixture"), "owner_uid", "synthetic.token") }.exceptionOrNull()
            assertTrue(failure is IOException)
            // Coroutine stacktrace recovery may retain a copy of our sanitized exception as its cause.
            generateSequence(requireNotNull(failure)) { it.cause }.take(10).flatMap { sequenceOf(it) + it.suppressed.asSequence() }.forEach {
                assertFalse(it.toString().contains("synthetic.token"))
                assertFalse(it.toString().contains("private.invalid"))
                assertFalse(it.toString().contains("/secret"))
            }
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun redirectsNeverForwardTheIdToken() = runBlocking {
        MockWebServer().use { source -> MockWebServer().use { destination ->
            source.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/capture")))
            assertTrue(runCatching { network(source).resolve(ReadingContext("context_fixture"), "owner_uid", "synthetic.token") }.exceptionOrNull() is IOException)
            assertEquals(0, destination.requestCount)
        } }
    }

    @Test fun duplicateJsonAndOversizedBodiesFailClosed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"schemaVersion\":1,\"schemaVersion\":1}"))
            assertTrue(runCatching { network(server).resolve(ReadingContext("context_fixture"), "owner_uid", "synthetic.token") }.isFailure)
            server.enqueue(MockResponse().setBody("x".repeat(2 * 1024 * 1024 + 1)))
            assertTrue(runCatching { network(server).resolve(ReadingContext("context_fixture"), "owner_uid", "synthetic.token") }.isFailure)
        }
    }

    @Test fun cancellationAfterHeadersStopsTheBodyReadPromptly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(gson.toJson(definition())).throttleBody(1, 3, TimeUnit.SECONDS))
            val request = async(Dispatchers.Default) { network(server).resolve(ReadingContext("context_fixture"), "owner_uid", "synthetic.token") }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            delay(100)
            withTimeout(1_000) { request.cancelAndJoin() }
            assertTrue(request.isCancelled)
        }
    }

    @Test fun configuredEndpointRejectsUnexpectedHostsPathsAndEmbeddedCredentials() {
        listOf("https://example.com/", "http://project.firebaseio.com/", "https://project.firebaseio.com/private/",
            "https://project.firebaseio.com/?auth=secret", "https://name:secret@project.firebaseio.com/", "https://project.firebaseio.com/#secret").forEach { base ->
            assertThrows(IllegalArgumentException::class.java) { ReadingContextNetwork(OkHttpClient(), base.toHttpUrl()) }
        }
    }
}
