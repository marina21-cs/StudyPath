package com.studypath.app.data

import java.io.File

/** Optional fields keep older text lessons readable without migration. */
data class NotePhoto(val id: String, val caption: String = "")
data class NoteSource(val materialId: String, val page: Int)

object StudyNotes {
    const val MAX_PHOTOS = 6
    const val MAX_IMAGE_BYTES = 2L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 100L * 1024 * 1024
    fun image(directory: File, id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid note image." }
        return File(File(directory, "note-images"), "$id.jpg")
    }
    fun validate(note: PersonalMaterial) {
        require(note.title.isNotBlank() && note.title.length <= 120) { "Add a title of up to 120 characters." }
        require(note.text.length <= 20000 && note.question.length <= 1000 && note.answer.length <= 4000) { "This note is too long." }
        require(note.question.isBlank() == note.answer.isBlank()) { "Add both a question and its answer." }
        val photos=note.photos.orEmpty()
        require(photos.size <= MAX_PHOTOS && photos.map { it.id }.distinct().size == photos.size) { "Add up to six different photos." }
        photos.forEach { image(File("."),it.id); require(it.caption.length <= 500) { "Keep captions under 500 characters." } }
        require(!note.pdf || photos.isEmpty() && note.source == null) { "Attach notes separately from the original PDF." }
        require(note.pdf || note.text.isNotBlank() || photos.isNotEmpty() || note.question.isNotBlank()) { "Write a note, add a photo, or add a question and answer." }
        note.source?.let { require(it.page in 1..500 && it.materialId.isNotBlank()) { "Choose a valid PDF page." } }
    }
    /** Move only new images. A failed metadata save leaves the previous note and images intact. */
    fun commit(directory: File, drafts: File, note: PersonalMaterial, previous: PersonalMaterial?, save: () -> Unit) {
        validate(note)
        val moved=mutableListOf<Pair<File,File>>()
        try {
            val incoming=note.photos.orEmpty().filter { !image(directory,it.id).exists() }
            val used=File(directory,"note-images").listFiles().orEmpty().sumOf { it.length() }
            require(used+incoming.sumOf { File(drafts,"${it.id}.jpg").length() } <= MAX_TOTAL_BYTES) { "Note photo storage is full. Remove unused photos first." }
            for(photo in incoming) {
                val from=File(drafts,"${photo.id}.jpg"); val to=image(directory,photo.id)
                require(from.isFile && from.length() in 1..MAX_IMAGE_BYTES) { "A photo is missing. Choose it again before saving." }
                check(to.parentFile!!.isDirectory || to.parentFile!!.mkdirs())
                check(from.renameTo(to)) { "Could not save the photo. Try again." }; moved+=from to to
            }
            save()
        } catch(error:Exception) { moved.asReversed().forEach { (from,to)-> to.renameTo(from) }; throw error }
        previous?.photos.orEmpty().filter { old -> note.photos.orEmpty().none { it.id==old.id } }.forEach { image(directory,it.id).delete() }
    }
}
