package com.studypath.app.data

import com.google.gson.GsonBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Personal metadata refers to an exact immutable excerpt, never to the changing live page. */
data class ReadingAnnotation(val snapshotRevisionId: Long, val excerptLength: Int, val note: String,
    val position: Int, val editId: String, val modifiedAt: Long) {
    fun record(saved: SavedReading): Map<String, Any> {
        require(snapshotRevisionId == saved.reading.revisionId && excerptLength == saved.excerpt.length)
        require(note.length <= 4000 && position in 0..excerptLength); TextPackageJson.utf8(note)
        DatabaseRecords.requireKey(editId); require(modifiedAt in 1..4_102_444_800_000L)
        return mapOf("snapshotRevisionId" to snapshotRevisionId, "excerptLength" to excerptLength,
            "note" to note, "position" to position, "editId" to editId, "modifiedAt" to modifiedAt)
    }
}
enum class ReadingSync { LOCAL, PENDING, CONFIRMED, REJECTED }
data class RetainedReading(val saved: SavedReading, val contextId: String?, val snapshotSync: ReadingSync = ReadingSync.LOCAL,
    val annotation: ReadingAnnotation? = null, val annotationSync: ReadingSync = ReadingSync.LOCAL,
    val deletionEditId: String? = null, val deletionSync: ReadingSync? = null,
    val snapshotEditId:String=UUID.randomUUID().toString())

/** Atomically persisted, UID-bound journal. PENDING after restart means acknowledgement is unknown. */
class ReadingStateStore(root: File) {
    private val root = root.canonicalFile
    private val gson = GsonBuilder().serializeNulls().create()
    private val changed = MutableStateFlow(0L)
    val changes: StateFlow<Long> = changed
    @Synchronized fun get(uid: String, id: String): RetainedReading? {
        val f=file(uid,id)
        if (!f.exists()) return null
        return try {
            require(f.isFile && f.length() in 1..200_000)
            val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val obj=TextPackageJson.objectValue(decoder.decode(java.nio.ByteBuffer.wrap(f.readBytes())).toString())
            require(obj.keySet()==setOf("schemaVersion","uid","entry") && TextPackageJson.integer(obj["schemaVersion"])==1L && TextPackageJson.string(obj["uid"])==uid)
            val raw=obj["entry"].asJsonObject
            require(raw.keySet()==setOf("saved","contextId","snapshotSync","annotation","annotationSync","deletionEditId","deletionSync","snapshotEditId"))
            if(!raw["annotation"].isJsonNull) {
                val annotation=raw["annotation"].asJsonObject
                require(annotation.keySet()==setOf("snapshotRevisionId","excerptLength","note","position","editId","modifiedAt"))
                listOf("snapshotRevisionId","excerptLength","position","modifiedAt").forEach { TextPackageJson.integer(annotation[it]) }
                listOf("note","editId").forEach { TextPackageJson.string(annotation[it]) }
            }
            val value=gson.fromJson(raw,RetainedReading::class.java)
            require(gson.toJsonTree(value)==raw) { "Reading state contains coerced or unknown fields." }
            require(value.saved.id==id); DatabaseRecords.reading(value.saved); DatabaseRecords.requireKey(value.snapshotEditId)
            value.contextId?.let(DatabaseRecords::requireKey)
            require(value.snapshotSync in ReadingSync.entries && value.annotationSync in ReadingSync.entries)
            value.annotation?.record(value.saved)
            value.deletionEditId?.let(DatabaseRecords::requireKey)
            require((value.deletionEditId==null)==(value.deletionSync==null))
            value
        } catch (e:Exception) { throw IllegalArgumentException("Saved reading state could not be restored. Its local file was retained.",e) }
    }
    @Synchronized fun list(uid: String): List<RetainedReading> {
        val directory=file(uid,"probe").parentFile!!
        return directory.listFiles().orEmpty().filter { it.name.endsWith(".json") }.map { requireNotNull(get(uid,it.name.removeSuffix(".json"))) }
    }
    @Synchronized fun retain(uid:String,saved:SavedReading,contextId:String?):RetainedReading {
        DatabaseRecords.reading(saved); contextId?.let(DatabaseRecords::requireKey)
        val old=get(uid,saved.id)
        require(old==null || old.saved==saved && old.contextId==contextId) { "The retained source and context are immutable; retry their exact saved values." }
        val entry=old ?: RetainedReading(saved,contextId)
        if (old==null) put(uid,entry)
        return entry
    }
    @Synchronized fun snapshotResult(uid:String,id:String,editId:String,success:Boolean):Boolean {
        val entry=get(uid,id) ?: return false
        if(entry.snapshotEditId!=editId) return false
        put(uid,entry.copy(snapshotSync=if(success) ReadingSync.CONFIRMED else ReadingSync.REJECTED)); return true
    }
    @Synchronized fun snapshotPending(uid:String,id:String) = update(uid,id) { it.copy(snapshotSync=ReadingSync.PENDING) }
    @Synchronized fun edit(uid:String,id:String,value:ReadingAnnotation) = update(uid,id) {
        require(it.deletionEditId==null) { "Resolve the pending removal before editing this reading." }
        value.record(it.saved); it.copy(annotation=value,annotationSync=ReadingSync.LOCAL)
    }
    @Synchronized fun annotationPending(uid:String,id:String,editId:String) = update(uid,id) { if(it.annotation?.editId==editId) it.copy(annotationSync=ReadingSync.PENDING) else it }
    @Synchronized fun annotationResult(uid:String,id:String,editId:String,success:Boolean) = update(uid,id) {
        if(it.annotation?.editId==editId) it.copy(annotationSync=if(success) ReadingSync.CONFIRMED else ReadingSync.REJECTED) else it
    }
    @Synchronized fun beginDelete(uid:String,id:String,editId:String) = update(uid,id) {
        DatabaseRecords.requireKey(editId)
        require(it.deletionEditId==null || it.deletionEditId==editId)
        it.copy(deletionEditId=editId,deletionSync=ReadingSync.PENDING)
    }
    @Synchronized fun deleteResult(uid:String,id:String,editId:String,success:Boolean):Boolean {
        val entry=get(uid,id) ?: return false
        if(entry.deletionEditId!=editId) return false
        if(success) { check(file(uid,id).delete()); changed.value++ }
        else put(uid,entry.copy(deletionSync=ReadingSync.REJECTED))
        return true
    }
    private fun update(uid:String,id:String,change:(RetainedReading)->RetainedReading) {
        val entry=get(uid,id) ?: return
        put(uid,change(entry))
    }
    private fun put(uid:String,entry:RetainedReading) {
        val f=file(uid,entry.saved.id); val directory=f.parentFile!!
        check(directory.isDirectory || directory.mkdirs())
        val temporary=File(directory,".${entry.saved.id}.${UUID.randomUUID()}.tmp")
        try {
            val text=gson.toJson(mapOf("schemaVersion" to 1,"uid" to uid,"entry" to entry))
            FileOutputStream(temporary).use { it.write(TextPackageJson.utf8(text)); it.fd.sync() }
            Files.move(temporary.toPath(),f.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            changed.value++
        } finally { temporary.delete() }
    }
    private fun file(uid:String,id:String):File {
        require(uid.matches(Regex("[A-Za-z0-9_-]{1,128}"))); DatabaseRecords.requireKey(id)
        val directory=File(root,uid); val f=File(directory,"$id.json")
        require(!Files.isSymbolicLink(directory.toPath()) && !Files.isSymbolicLink(f.toPath()) && f.canonicalFile==f.absoluteFile && f.toPath().startsWith(root.toPath()))
        return f
    }
}

object ReadingShelf {
    val slots=(0..19).map { "s%02d".format(java.util.Locale.ROOT,it) }
    fun freeSlot(ids:Set<String>,index:Map<String,String>,version:Int?):String {
        require(version==1 || version==null && ids.isEmpty()) { "This older shelf needs a lossless trusted migration before adding readings. Existing readings remain available." }
        require(index.keys.all { it in slots } && index.values.all { it in ids }) { "The shelf index needs recovery; retained readings were preserved." }
        require(ids.size<20) { "Keep at most 20 readings. Remove a saved reading first." }
        return requireNotNull(slots.firstOrNull { it !in index }) { "All 20 reading slots are occupied." }
    }
    fun deletion(id:String,index:Map<String,String>):Map<String,Any?> {
        DatabaseRecords.requireKey(id)
        return mutableMapOf<String,Any?>("savedReadings/$id" to null,"readingAnnotations/$id" to null,"readingContexts/$id" to null).apply {
            index.filterValues { it==id }.keys.forEach { require(it in slots); put("savedReadingSlots/$it",null) }
        }
    }
}
