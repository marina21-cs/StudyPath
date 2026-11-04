package com.studypath.app.data

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ReadingStateStoreTest {
    private fun saved(id: Long = 1) = SavedReading("wiki_${id}_8", ReadingContent(id, "Computer_programming", "Programming", "A bounded source excerpt.", 8,
        "2026-10-27T00:00:00Z", "https://en.wikipedia.org/wiki/Computer_programming", "https://en.wikipedia.org/w/index.php?oldid=8",
        "CC BY-SA 4.0", "https://creativecommons.org/licenses/by-sa/4.0/", "Wikipedia contributors", "Excerpt", 100), 100)
    private fun store() = ReadingStateStore(Files.createTempDirectory("reading-state").toFile())

    @Test fun validLiveLicenseDeedSurvivesSnapshotAndDurableJournal() {
        for(url in listOf("https://creativecommons.org/licenses/by-sa/4.0/","https://creativecommons.org/licenses/by-sa/4.0/deed.en","https://creativecommons.org/licenses/by-sa/3.0/deed.en")) {
            val source=saved().let { it.copy(reading=it.reading.copy(licenseUrl=url)) }
            val s=store(); s.retain("alice",source,null)
            assertEquals(url,s.get("alice",source.id)!!.saved.reading.licenseUrl)
        }
        for(url in listOf("https://creativecommons.org/licenses/by/4.0/","https://creativecommons.org/licenses/by-sa/4.0/?tracking=1","https://creativecommons.org/licenses/by-sa/4.0/#fragment","https://user@creativecommons.org/licenses/by-sa/4.0/")) {
            val source=saved().let { it.copy(reading=it.reading.copy(licenseUrl=url)) }
            assertThrows(IllegalArgumentException::class.java) { store().retain("alice",source,null) }
        }
    }
    @Test fun draftsSurviveDiskReopenAndRemainUnknownUntilExactAcknowledgement() {
        val root = Files.createTempDirectory("reading-state").toFile()
        val first = ReadingStateStore(root); val source = saved()
        first.retain("alice", source, null)
        val note = ReadingAnnotation(8, source.excerpt.length, "Private note", 7, "edit_1", 200)
        first.edit("alice", source.id, note)
        first.annotationPending("alice", source.id, note.editId)
        val reopened = ReadingStateStore(root)
        assertEquals(source, reopened.get("alice", source.id)!!.saved)
        assertEquals(note, reopened.get("alice", source.id)!!.annotation)
        assertEquals(ReadingSync.PENDING, reopened.get("alice", source.id)!!.annotationSync)
        assertNull(reopened.get("bob", source.id))
        reopened.annotationResult("alice", source.id, "older_edit", true)
        assertEquals(ReadingSync.PENDING, reopened.get("alice", source.id)!!.annotationSync)
        reopened.annotationResult("alice", source.id, note.editId, true)
        assertEquals(ReadingSync.CONFIRMED, ReadingStateStore(root).get("alice", source.id)!!.annotationSync)
    }
    @Test fun newerLocalEditCannotBeConfirmedByOlderTask() {
        val s = store(); val r = saved(); s.retain("alice", r, null)
        val old = ReadingAnnotation(8, r.excerpt.length, "old", 1, "old", 200)
        val fresh = old.copy(note="new", editId="new", modifiedAt=201)
        s.edit("alice", r.id, old); s.annotationPending("alice", r.id, old.editId)
        s.edit("alice", r.id, fresh); s.annotationResult("alice", r.id, old.editId, true)
        assertEquals(fresh, s.get("alice", r.id)!!.annotation)
        assertEquals(ReadingSync.LOCAL, s.get("alice", r.id)!!.annotationSync)
        s.annotationResult("alice", r.id, fresh.editId, false)
        assertEquals(ReadingSync.REJECTED, s.get("alice", r.id)!!.annotationSync)
    }
    @Test fun versionPositionAndNoteBoundsFailBeforeReplacingLocalState() {
        val s=store(); val r=saved(); s.retain("alice",r,null)
        val good=ReadingAnnotation(8,r.excerpt.length,"",0,"edit",200)
        for (bad in listOf(good.copy(snapshotRevisionId=9), good.copy(excerptLength=999), good.copy(position=-1), good.copy(position=r.excerpt.length+1),good.copy(note="x".repeat(4001)),good.copy(editId="../escape"))) {
            assertThrows(IllegalArgumentException::class.java) { s.edit("alice",r.id,bad) }
        }
        assertNull(s.get("alice",r.id)!!.annotation)
        s.edit("alice",r.id,good.copy(note="x".repeat(4000),position=r.excerpt.length))
    }
    @Test fun sourceAndContextRemainImmutableDuringRetries() {
        val s=store(); val r=saved(); s.retain("alice",r,"context_1"); s.retain("alice",r,"context_1")
        assertThrows(IllegalArgumentException::class.java) { s.retain("alice",r.copy(reading=r.reading.copy(excerpt="changed")),"context_1") }
        assertThrows(IllegalArgumentException::class.java) { s.retain("alice",r,"context_2") }
        assertEquals(r,s.get("alice",r.id)!!.saved)
    }
    @Test fun deletionKeepsTextUntilMatchingAcknowledgementAndCanBeRetried() {
        val root=Files.createTempDirectory("reading-delete").toFile(); val s=ReadingStateStore(root); val r=saved()
        s.retain("alice",r,null); s.beginDelete("alice",r.id,"delete_1")
        assertEquals(r,ReadingStateStore(root).get("alice",r.id)!!.saved)
        s.deleteResult("alice",r.id,"stale",true); assertNotNull(s.get("alice",r.id))
        s.deleteResult("alice",r.id,"delete_1",false); assertEquals(ReadingSync.REJECTED,s.get("alice",r.id)!!.deletionSync)
        s.beginDelete("alice",r.id,"delete_1"); s.deleteResult("alice",r.id,"delete_1",true)
        assertNull(ReadingStateStore(root).get("alice",r.id))
    }
    @Test fun oldSaveAcknowledgementCannotConfirmADeletedAndRecreatedReading() {
        val s=store(); val r=saved(); val first=s.retain("alice",r,null)
        s.beginDelete("alice",r.id,"delete_old"); s.deleteResult("alice",r.id,"delete_old",true)
        val second=s.retain("alice",r,null)
        assertNotEquals(first.snapshotEditId,second.snapshotEditId)
        assertFalse(s.snapshotResult("alice",r.id,first.snapshotEditId,true))
        assertEquals(ReadingSync.LOCAL,s.get("alice",r.id)!!.snapshotSync)
        assertTrue(s.snapshotResult("alice",r.id,second.snapshotEditId,true))
    }
    @Test fun corruptFileIsRetainedAndCannotBeOverwrittenByRetry() {
        val root=Files.createTempDirectory("reading-corrupt").toFile(); val s=ReadingStateStore(root); val r=saved(); s.retain("alice",r,null)
        val file=root.resolve("alice").resolve("${r.id}.json"); file.writeText("{broken")
        assertThrows(IllegalArgumentException::class.java) { s.retain("alice",r,null) }
        assertEquals("{broken",file.readText())
        assertThrows(IllegalArgumentException::class.java) { s.get("../alice",r.id) }
    }
    @Test fun capacityAndLinkedDeletePatchAreBoundedAndPreserveDuplicateSlots() {
        assertEquals("s00",ReadingShelf.freeSlot(emptySet(),emptyMap(),null))
        assertThrows(IllegalArgumentException::class.java) { ReadingShelf.freeSlot(setOf("legacy"),emptyMap(),null) }
        val ids=(0..19).map { "r$it" }.toSet(); val slots=(0..19).associate { "s%02d".format(it) to "r$it" }
        assertThrows(IllegalArgumentException::class.java) { ReadingShelf.freeSlot(ids,slots,1) }
        val patch=ReadingShelf.deletion("r0",mapOf("s00" to "r0","s01" to "r0","s02" to "r2"))
        assertEquals(setOf("savedReadings/r0","readingAnnotations/r0","readingContexts/r0","savedReadingSlots/s00","savedReadingSlots/s01"),patch.keys)
        assertTrue(patch.values.all { it == null })
    }
}
