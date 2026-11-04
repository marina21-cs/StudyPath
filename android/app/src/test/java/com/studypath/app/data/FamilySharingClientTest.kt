package com.studypath.app.data

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class FamilySharingClientTest {
    private lateinit var server: MockWebServer
    private val now = 1_798_128_000_000L
    private val context = "mock_computing_v1"
    private val fields get() = FamilySharingRecords.fields
    private val link = FamilySummaryLink("alice", context, "g0", 1)
    private fun json(value: String) = JsonParser.parseString(value).asJsonObject
    private fun policy() = json("""{"schemaVersion":1,"contextId":"$context","curriculumVersion":"$context","levelId":"mock_college","approvalStatus":"APPROVED","approvalReference":"synthetic-test","reviewer":"synthetic-test","reviewedAt":${now - 10000},"evidenceMode":"SYNTHETIC_LOCAL_ONLY","revision":1,"validFrom":${now - 10000},"validUntil":${now + 86400000},"eligibleLearnerRole":"learner","eligibleRecipientRole":"recipient","permissionModelReference":"synthetic-permission","consentModelReference":"synthetic-consent","allowedFields":{"completedLessonCount":true,"asOf":true},"maxLifetimeMs":86400000,"maxActiveGrants":5,"maxGeneration":1000000}""")
    private fun role() = json("""{"recipientUid":"bob","contextId":"$context","recipientRole":"recipient","validUntil":${now + 86400000}}""")
    private fun preferences() = json("""{"onboardingComplete":true,"curriculumVersion":"$context","levelId":"mock_college"}""")
    private fun eligibility() = json("""{"ownerUid":"alice","contextId":"$context","curriculumVersion":"$context","levelId":"mock_college","learnerRole":"learner","permissionModelReference":"synthetic-permission","consentModelReference":"synthetic-consent","consentRecordReference":"synthetic-record","consentValidUntil":${now + 86400000}}""")
    private fun acceptance() = json("""{"schemaVersion":1,"ownerUid":"alice","recipientUid":"bob","contextId":"$context","policyRevision":1,"acceptanceId":"a00","consentRecordReference":"synthetic-record","fields":{"completedLessonCount":true,"asOf":true},"acceptedAt":${now - 2000},"expiresAt":${now + 3600000}}""")
    private fun projection(generation: Long = 1) = json("""{"schemaVersion":1,"ownerUid":"alice","recipientUid":"bob","slotId":"g0","generation":$generation,"contextId":"$context","policyRevision":1,"expiresAt":${now + 3600000},"summary":{"completedLessonCount":2,"asOf":${now - 1000}}}""")
    private fun slot(generation: Long = 1): JsonObject = JsonObject().apply {
        add("grant", json("""{"schemaVersion":1,"ownerUid":"alice","recipientUid":"bob","slotId":"g0","generation":$generation,"contextId":"$context","policyRevision":1,"acceptanceId":"a00","consentRecordReference":"synthetic-record","fields":{"completedLessonCount":true,"asOf":true},"createdAt":${now - 1500},"expiresAt":${now + 3600000},"status":"ACTIVE"}"""))
        add("projection", projection(generation))
    }
    private fun revoked(generation: Long = 1) = JsonObject().apply {
        add("grant", slot(generation).getAsJsonObject("grant").deepCopy().apply { addProperty("status", "REVOKED"); addProperty("revokedAt", now) })
    }
    private fun response(value: JsonElement, status: Int = 200, etag: String? = null) = MockResponse().setResponseCode(status)
        .setHeader("Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC)))
        .setHeader("Content-Type", "application/json").setBody(value.toString()).apply { if (etag != null) setHeader("ETag", etag) }
    private fun enqueue(value: JsonElement, status: Int = 200, etag: String? = null) { server.enqueue(response(value, status, etag)) }
    private fun client(uid: String = "bob", allowSynthetic: Boolean = true, active: () -> Unit = {}, fresh: () -> Unit = {}): FamilySharingClient {
        val network = FamilySharingNetwork(OkHttpClient(), server.url("/").newBuilder().addQueryParameter("ns", "demo-studypath-family-unit").build(), true)
        return FamilySharingClient(network, { fresh(); FamilyCredentials(uid, "fresh-test-token", active) }, allowSynthetic)
    }
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun cleanup() { server.shutdown() }

    @Test fun credentialsNeverPrintTokenOrUid() {
        assertEquals("FamilyCredentials(redacted)", FamilyCredentials("secret-uid", "secret-token") {}.toString())
    }
    @Test fun recipientFetchUsesFreshCredentialAndOnlyExactPublicPolicyOwnRoleAndProjection() = runBlocking {
        enqueue(policy()); enqueue(role()); enqueue(projection())
        var fresh = 0; var fenced = 0
        val result = client(fresh = { fresh++ }, active = { fenced++ }).readSummary(link.code())
        assertEquals(2, result.completedLessonCount); assertEquals(1, fresh); assertTrue(fenced >= 8)
        val requests = (1..3).map { server.takeRequest() }
        assertEquals(listOf("/catalog/sharingPolicies/$context.json", "/sharingRecipientRoles/bob/$context.json", "/sharedStudy/alice/g0/projection.json"), requests.map { it.requestUrl!!.encodedPath })
        assertTrue(requests.all { it.requestUrl!!.queryParameter("auth") == "fresh-test-token" && it.getHeader("Cache-Control") == "no-cache, no-store" })
        assertTrue(requests.none { it.path!!.contains("/users/") || it.path!!.contains("sharingEligibility") })
    }
    @Test fun productionClientRefusesSyntheticPolicyAndUnsupportedDueScopeBeforeFetchingSummary() = runBlocking {
        enqueue(policy())
        try { client(allowSynthetic = false).readSummary(link.code()); fail("Synthetic policy must stay disabled") } catch (_: IllegalArgumentException) { }
        val unsupported = policy().apply { getAsJsonObject("allowedFields").addProperty("dueItemCount", true) }
        enqueue(unsupported)
        try { client().readSummary(link.code()); fail("Unsupported field must fail closed") } catch (_: IllegalArgumentException) { }
        assertEquals(2, server.requestCount)
    }
    @Test fun withdrawnProjectionProducesNoCachedResultAfterEarlierSuccess() = runBlocking {
        repeat(2) { enqueue(policy()); enqueue(role()); enqueue(projection(), if (it == 0) 200 else 401) }
        val client = client(); assertEquals(2, client.readSummary(link.code()).completedLessonCount)
        try { client.readSummary(link.code()); fail("Denied request must not reuse previous summary") } catch (_: IOException) { }
        assertEquals(6, server.requestCount)
    }
    @Test fun summaryUsesDateHeaderUncertaintyForSubsecondAsOfAndConservativeExpiry() = runBlocking {
        enqueue(policy()); enqueue(role()); enqueue(projection().apply { getAsJsonObject("summary").addProperty("asOf", now + 500) })
        assertTrue(client().readSummary(link.code()).serverNow >= now + 999)
        enqueue(policy()); enqueue(role()); enqueue(projection().apply { addProperty("expiresAt", now + 500) })
        try { client().readSummary(link.code()); fail("Expiry within Date uncertainty must clear the summary") } catch (_: IllegalArgumentException) { }
    }
    @Test fun sessionFenceRejectsResponseArrivingAfterLogout() = runBlocking {
        var active = true
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { active = false; return response(policy()) }
        }
        try { client(active = { check(active) { "Session ended" } }).readSummary(link.code()); fail("Late response must be fenced") }
        catch (_: IllegalStateException) { }
        assertEquals(1, server.requestCount)
    }
    @Test fun ownerCanInspectExistingGrantsWhenCurrentPolicyIsUnavailable() = runBlocking {
        enqueue(JsonObject().apply { add("g0", slot()) }); enqueue(JsonNull.INSTANCE); enqueue(preferences()); enqueue(JsonNull.INSTANCE)
        val state = client("alice").ownerState()
        assertEquals(1, state.grants.size); assertEquals("ACTIVE", state.grants.single().status)
        assertNotNull(state.unavailableReason); assertFalse(state.synthetic)
    }
    @Test fun staleRevokeCannotWriteToNewerGeneration() = runBlocking {
        enqueue(slot(2), etag = "\"revision2\"")
        try { client("alice").revoke("g0", 1); fail("Stale generation must be retained") } catch (_: IllegalArgumentException) { }
        assertEquals(1, server.requestCount); assertEquals("GET", server.takeRequest().method)
    }
    @Test fun revokeWorksWithoutPolicyReadsAndAtomicallyDeletesProjection() = runBlocking {
        enqueue(slot(), etag = "\"revision1\""); enqueue(revoked())
        client("alice").revoke("g0", 1)
        val get = server.takeRequest(); val put = server.takeRequest()
        assertEquals("true", get.getHeader("X-Firebase-ETag")); assertEquals("PUT", put.method)
        assertEquals("\"revision1\"", put.getHeader("if-match"))
        assertEquals(revoked(), JsonParser.parseString(put.body.readUtf8()))
        assertEquals("/sharedStudy/alice/g0.json", put.requestUrl!!.encodedPath)
        assertEquals(2, server.requestCount)
    }
    @Test fun ambiguousRevocationRequiresExactFreshTombstoneToAcknowledge() = runBlocking {
        enqueue(slot(), etag = "\"revision1\""); enqueue(JsonNull.INSTANCE, 500); enqueue(revoked())
        client("alice").revoke("g0", 1)
        assertEquals(listOf("GET", "PUT", "GET"), (1..3).map { server.takeRequest().method })
    }
    @Test fun conflictingRevocationNeverTouchesANewerGeneration() = runBlocking {
        enqueue(slot(), etag = "\"revision1\""); enqueue(JsonNull.INSTANCE, 412); enqueue(slot(2))
        try { client("alice").revoke("g0", 1); fail("A new generation must survive the late revoke") } catch (_: IllegalArgumentException) { }
        assertEquals(3, server.requestCount)
    }
    @Test fun invitationAcceptanceIsIndependentAndCannotReadOwnerPrivateTree() = runBlocking {
        val invitation = FamilyInvitation("alice", context, "a00", 1, "synthetic-record", now + 3600000)
        enqueue(policy()); enqueue(role()); enqueue(acceptance()); enqueue(acceptance())
        val result = client().acceptInvitation(invitation.code())
        assertEquals("bob", result.recipientUid); assertEquals("a00", result.id)
        val requests = (1..4).map { server.takeRequest() }
        assertTrue(requests.none { it.requestUrl!!.encodedPath.startsWith("/users/") || it.requestUrl!!.encodedPath.startsWith("/sharingEligibility/") })
        assertEquals("PUT", requests.last().method)
        assertEquals(acceptance(), JsonParser.parseString(requests.last().body.readUtf8()))
    }
    @Test fun ownerCannotAcceptOwnInvitationAndDoesNotContactNetwork() = runBlocking {
        val invitation = FamilyInvitation("alice", context, "a00", 1, "synthetic-record", now + 3600000)
        try { client("alice").acceptInvitation(invitation.code()); fail("Owner cannot act as recipient") } catch (_: IllegalArgumentException) { }
        assertEquals(0, server.requestCount)
    }
    @Test fun preexistingAcceptanceCannotAcknowledgeAfterCurrentConsentWriteAuthorizationIsDenied() = runBlocking {
        val invitation = FamilyInvitation("alice", context, "a00", 1, "synthetic-record", now + 3600000)
        // The recipient may still inspect its historical acceptance after owner consent withdrawal.
        enqueue(policy()); enqueue(role()); enqueue(acceptance()); enqueue(JsonNull.INSTANCE, 403)
        try { client().acceptInvitation(invitation.code()); fail("A readable old acceptance does not prove current consent authorization") }
        catch (_: IOException) { }
        assertEquals(4, server.requestCount)
    }
    @Test fun grantCountsOnlyUniqueInventoryBackedAcknowledgedLessonsUsingProtectedPatch() = runBlocking {
        var completionIndex: JsonObject? = null
        var storedSlot: JsonElement = JsonNull.INSTANCE
        val hash = "a".repeat(64)
        val key = "mock_programming:v1:lesson_1"
        val run = json("""{"schemaVersion":1,"curriculumVersion":"$context","moduleId":"mock_programming","version":1,"lessonId":"lesson_1","runId":"run_1","packageSha256":"$hash","resolvedSteps":3,"totalSteps":3,"completedAt":${now - 5000}}""")
        val attempts = JsonObject().apply {
            add("run_1", run)
            add("run_2", run.deepCopy().apply { addProperty("runId", "run_2") })
            add("forged", run.deepCopy().apply { addProperty("runId", "forged"); addProperty("lessonId", "invented_lesson") })
            add("incomplete", run.deepCopy().apply { addProperty("runId", "incomplete"); addProperty("resolvedSteps", 2) })
        }
        var patches = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.requestUrl!!.encodedPath
                val value: JsonElement = when {
                    target.endsWith("/learnerPreferences.json") -> preferences()
                    target.startsWith("/catalog/sharingPolicies/") -> policy()
                    target.startsWith("/sharingEligibility/") -> eligibility()
                    target.startsWith("/catalog/sharingLessonKeys/") -> JsonObject().apply { addProperty(key, hash) }
                    target.endsWith("/lessonAttempts.json") -> attempts
                    target.endsWith("/a00.json") -> acceptance()
                    target.contains("/lessonCompletionIndex/") -> {
                        if (request.method == "PATCH") {
                            patches++
                            val patch = JsonParser.parseString(request.body.readUtf8()).asJsonObject
                            assertEquals(setOf("count", "lastKey", "entries/$key"), patch.keySet())
                            assertEquals(1, patch.get("count").asInt)
                            completionIndex = JsonObject().apply {
                                add("count", patch.get("count")); add("lastKey", patch.get("lastKey"))
                                add("entries", JsonObject().apply { add(key, patch.get("entries/$key")) })
                            }
                        }
                        completionIndex ?: JsonNull.INSTANCE
                    }
                    target == "/sharedStudy/alice/g0.json" -> {
                        if (request.method == "PUT") storedSlot = JsonParser.parseString(request.body.readUtf8())
                        storedSlot
                    }
                    else -> return response(JsonNull.INSTANCE, 404)
                }
                return response(value, etag = "\"current\"")
            }
        }
        val result = client("alice").grant("a00", "g0")
        assertEquals("ACTIVE", result.status); assertEquals(1L, result.generation); assertEquals(1, patches)
        assertEquals(1, storedSlot.asJsonObject.getAsJsonObject("projection").getAsJsonObject("summary").get("completedLessonCount").asInt)
        assertEquals("run_1", completionIndex!!.getAsJsonObject("entries").getAsJsonObject(key).get("runId").asString)
    }
    @Test fun existingActiveGrantStillRequiresServerAuthorizationBeforeAcknowledgement() = runBlocking {
        var puts = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.requestUrl!!.encodedPath
                if (request.method == "PUT") { puts++; return response(JsonNull.INSTANCE, 401) }
                val value = when {
                    target.endsWith("/learnerPreferences.json") -> preferences()
                    target.startsWith("/catalog/sharingPolicies/") -> policy()
                    target.startsWith("/sharingEligibility/") -> eligibility()
                    target == "/sharedStudy/alice/g0.json" -> slot()
                    else -> JsonNull.INSTANCE
                }
                return response(value, etag = "\"current\"")
            }
        }
        try { client("alice").grant("a00", "g0"); fail("An owner read cannot prove current recipient authorization") } catch (_: IOException) { }
        assertEquals(3, puts)
    }
    @Test fun delayedBodyCannotKeepSummaryVisiblePastItsExpiry() = runBlocking {
        enqueue(policy()); enqueue(role())
        server.enqueue(response(projection().apply { addProperty("expiresAt", now + 1500) }).setBodyDelay(2, TimeUnit.SECONDS))
        try { client().readSummary(link.code()); fail("Network response time must reduce the remaining permission lifetime") } catch (_: IllegalArgumentException) { }
    }
}
