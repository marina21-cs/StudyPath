package com.studypath.app.data

import com.google.gson.JsonNull
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class FamilySharingNetworkTest {
    private fun response(body: String = "null") = MockResponse().setHeader("Date", "Fri, 30 Oct 2026 16:00:00 GMT").setBody(body)
    @Test fun sendsExactTokenPathEtagAndNeverReusesAStoredResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response("{\"value\":1}").setHeader("ETag", "\"a\"").setHeader("Cache-Control", "max-age=86400"))
            server.enqueue(response().setResponseCode(403))
            val network = FamilySharingNetwork(OkHttpClient(), server.url("/?ns=demo-studypath-family-test"), true)
            assertEquals("\"a\"", network.request(listOf("sharedStudy", "owner", "g0"), "synthetic-token", readEtag = true).etag)
            assertTrue(runCatching { network.request(listOf("sharedStudy", "owner", "g0"), "synthetic-token") }.exceptionOrNull() is FamilyRestFailure)
            val request = server.takeRequest()
            assertEquals("/sharedStudy/owner/g0.json?ns=demo-studypath-family-test&auth=synthetic-token", request.path)
            assertEquals("true", request.getHeader("X-Firebase-ETag"))
            assertEquals("no-cache, no-store", request.getHeader("Cache-Control"))
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun conditionalWriteKeepsConflictUnconfirmed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response().setResponseCode(412))
            val network = FamilySharingNetwork(OkHttpClient(), server.url("/?ns=demo-studypath-family-test"), true)
            assertEquals(412, (runCatching { network.request(listOf("sharedStudy", "owner", "g0"), "token", "PUT", JsonNull.INSTANCE, "\"old\"") }.exceptionOrNull() as FamilyRestFailure).status)
            val request = server.takeRequest(); assertEquals("PUT", request.method); assertEquals("\"old\"", request.getHeader("if-match"))
        }
    }
    @Test fun refusesUnexpectedOriginsNamespacesAndPrivatePaths() = runBlocking {
        MockWebServer().use { server ->
            assertTrue(runCatching { FamilySharingNetwork(OkHttpClient(), server.url("/?ns=real-project"), true) }.isFailure)
            assertTrue(runCatching { FamilySharingNetwork(OkHttpClient(), server.url("/?ns=demo-studypath-family-test"), false) }.isFailure)
            val network = FamilySharingNetwork(OkHttpClient(), server.url("/?ns=demo-studypath-family-test"), true)
            assertTrue(runCatching { network.request(listOf("sharedStudy", "../foreign"), "token") }.isFailure)
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun redirectsAreNotFollowedAndErrorsContainNoToken() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response().setResponseCode(302).setHeader("Location", "/elsewhere"))
            val network = FamilySharingNetwork(OkHttpClient(), server.url("/?ns=demo-studypath-family-test"), true)
            val failure = runCatching { network.request(listOf("sharedStudy", "owner", "g0"), "private-token") }.exceptionOrNull()
            assertNotNull(failure); assertFalse(failure.toString().contains("private-token")); assertEquals(1, server.requestCount)
        }
    }
    @Test fun emulatorWithoutDateUsesExplicitSyntheticClockAndRetainsExpiryChecks() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("null"))
            val network = FamilySharingNetwork(OkHttpClient(), server.url("/?ns=demo-studypath-family-test"), true)
            val before = System.currentTimeMillis()
            val result = network.request(listOf("sharedStudy", "owner", "g0"), "synthetic-token")
            val after = System.currentTimeMillis()
            assertEquals(FamilyTimeSource.SYNTHETIC_LOCAL_CLOCK, result.timeSource)
            assertTrue(result.serverNow in before..after)
            val link = FamilySummaryLink("owner", "mock_computing_v1", "g0", 1)
            val expired = JsonParser.parseString("""{"schemaVersion":1,"ownerUid":"owner","recipientUid":"recipient","slotId":"g0","generation":1,"contextId":"mock_computing_v1","policyRevision":1,"expiresAt":$before,"summary":{"completedLessonCount":1,"asOf":${before - 1}}}""").asJsonObject
            assertTrue(runCatching { FamilySharingRecords.snapshot(expired, link, "recipient", result.serverNow) }.isFailure)
        }
    }
    @Test fun clockFallbackNeverAcceptsMissingCloudDateOrMalformedLocalDate() {
        assertTrue(runCatching { familyResponseTime(null, false) }.isFailure)
        assertTrue(runCatching { familyResponseTime("not-a-date", true) }.isFailure)
        assertTrue(runCatching { familyResponseTime("", true) }.isFailure)
        val parsed = familyResponseTime("Fri, 30 Oct 2026 16:00:00 GMT", true)
        assertEquals(FamilyTimeSource.HTTP_DATE, parsed.second)
    }
}
