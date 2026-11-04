package com.studypath.app.data

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class FamilySharingRecordsTest {
    @Test fun invitationRoundTripContainsScopeAndNoCredentials() {
        val invitation = FamilyInvitation("owner", "mock_computing_v1", "a00", 1, "mock-consent", 2_000)
        assertEquals(invitation, FamilySharingRecords.invitation(invitation.code()))
        assertFalse(invitation.code().contains("password"))
        assertTrue(runCatching { FamilySharingRecords.invitation(invitation.code() + "!") }.isFailure)
    }

    @Test fun viewLinkDoesNotBecomeAnInvitationOrAcceptAnotherGeneration() {
        val link = FamilySummaryLink("owner", "mock_computing_v1", "g0", 2)
        assertEquals(link, FamilySharingRecords.link(link.code()))
        assertTrue(runCatching { FamilySharingRecords.invitation(link.code()) }.isFailure)
        assertTrue(runCatching { FamilySharingRecords.link(FamilySummaryLink("owner", "mock_computing_v1", "g9", 1).code()) }.isFailure)
    }

    @Test fun snapshotIsExactScopedUnexpiredAndBounded() {
        val link = FamilySummaryLink("owner", "mock_computing_v1", "g0", 2)
        val json = JsonParser.parseString("""{"schemaVersion":1,"ownerUid":"owner","recipientUid":"recipient","slotId":"g0","generation":2,"contextId":"mock_computing_v1","policyRevision":1,"expiresAt":2000,"summary":{"completedLessonCount":3,"asOf":1000}}""").asJsonObject
        assertEquals(3, FamilySharingRecords.snapshot(json, link, "recipient", 1_500).completedLessonCount)
        assertTrue(runCatching { FamilySharingRecords.snapshot(json, link.copy(generation = 1), "recipient", 1_500) }.isFailure)
        assertTrue(runCatching { FamilySharingRecords.snapshot(json, link, "foreign", 1_500) }.isFailure)
        assertTrue(runCatching { FamilySharingRecords.snapshot(json, link, "recipient", 2_000) }.isFailure)
        json.getAsJsonObject("summary").addProperty("grade", 98)
        assertTrue(runCatching { FamilySharingRecords.snapshot(json, link, "recipient", 1_500) }.isFailure)
    }
}
