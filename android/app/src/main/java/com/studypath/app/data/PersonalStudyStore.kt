package com.studypath.app.data

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

data class PersonalSubject(val id: String, val title: String)
data class PersonalTask(val id: String, val subjectId: String, val title: String, val due: String = "", val done: Boolean = false)
data class PersonalMaterial(val id: String, val subjectId: String, val title: String, val text: String = "", val question: String = "", val answer: String = "", val pdf: Boolean = false)
data class PersonalWorkspace(val subjects: List<PersonalSubject> = emptyList(), val tasks: List<PersonalTask> = emptyList(), val materials: List<PersonalMaterial> = emptyList())

/** Local personal work is separate from published content and policy-bound academic records. */
class PersonalStudyStore(root: File, uid: String) {
    val directory = File(root, MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") { "%02x".format(it) })
    private val record = File(directory, "workspace.json")
    init { require(uid.isNotBlank()); check(directory.isDirectory || directory.mkdirs()) }
    fun read(): PersonalWorkspace = synchronized(lock) {
        if (!record.exists()) PersonalWorkspace() else {
            require(record.length() <= MAX_JSON) { "Your personal workspace is too large to open." }
            val result = Gson().fromJson(record.readText(), PersonalWorkspace::class.java)
                ?: error("Your personal workspace could not be read. Your files are kept.")
            validate(result); result
        }
    }
    fun update(transform: (PersonalWorkspace) -> PersonalWorkspace): PersonalWorkspace = synchronized(lock) {
        val value = transform(read()); validate(value)
        val bytes = Gson().toJson(value).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_JSON) { "Your personal workspace is full." }
        val temp = File(directory, "workspace.tmp")
        FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        Files.move(temp.toPath(), record.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        value
    }
    fun pdf(id: String): File { require(validId(id)); return File(directory, "$id.pdf") }
    fun removeSubject(id: String) = synchronized(lock) {
        val removed = read().materials.filter { it.subjectId == id && it.pdf }
        update { it.copy(subjects = it.subjects.filterNot { row -> row.id == id }, tasks = it.tasks.filterNot { row -> row.subjectId == id }, materials = it.materials.filterNot { row -> row.subjectId == id }) }
        removed.forEach { pdf(it.id).delete() }
    }
    fun removeMaterial(id: String) = synchronized(lock) {
        update { it.copy(materials = it.materials.filterNot { row -> row.id == id }) }; pdf(id).delete()
    }
    companion object {
        private val lock = Any()
        const val MAX_PDF_BYTES = 25L * 1024 * 1024
        const val MAX_TOTAL_PDF_BYTES = 250L * 1024 * 1024
        private const val MAX_JSON = 3_000_000
        fun newId(): String = UUID.randomUUID().toString()
        private fun validId(value: String) = value.matches(Regex("[a-f0-9-]{36}"))
        fun validate(value: PersonalWorkspace) {
            require(value.subjects.size <= 100 && value.tasks.size <= 500 && value.materials.size <= 200) { "Personal workspace limit reached." }
            val subjects = value.subjects.map { it.id }.toSet()
            require(subjects.size == value.subjects.size && value.tasks.map { it.id }.distinct().size == value.tasks.size && value.materials.map { it.id }.distinct().size == value.materials.size)
            value.subjects.forEach { require(validId(it.id) && it.title.isNotBlank() && it.title.length <= 80) { "Use a subject name of 1–80 characters." } }
            value.tasks.forEach {
                require(validId(it.id) && it.subjectId in subjects && it.title.isNotBlank() && it.title.length <= 160) { "Enter a task name of 1–160 characters and choose a subject." }
                require(it.due.isEmpty() || runCatching { LocalDate.parse(it.due).toString() == it.due }.getOrDefault(false)) { "Choose a valid date." }
            }
            value.materials.forEach {
                require(validId(it.id) && it.subjectId in subjects && it.title.isNotBlank() && it.title.length <= 120) { "Enter a lesson title and choose a subject." }
                require(it.text.length <= 20000 && it.question.length <= 1000 && it.answer.length <= 4000) { "This lesson is too long." }
                require(it.pdf || it.text.isNotBlank() || (it.question.isNotBlank() && it.answer.isNotBlank())) { "Add lesson text or a question and answer." }
                require(it.question.isBlank() == it.answer.isBlank()) { "Add both a question and its answer." }
            }
        }
    }
}
