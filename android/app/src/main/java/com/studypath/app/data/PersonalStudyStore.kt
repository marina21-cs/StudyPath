package com.studypath.app.data

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

data class PersonalSubject(val id: String, val title: String, val category: String? = null,
    val code: String? = null, val units: Double? = null, val grade: String? = null,
    val prerequisiteIds: List<String>? = null,
    val gradeDraft: com.studypath.app.domain.PersonalGradeDraft? = null, val catalogCourseId: String? = null) {
    fun categoryLabel() = category?.takeIf { it in CATEGORIES } ?: "General"
    companion object { val CATEGORIES = listOf("General", "Mathematics", "Science", "Technology", "Languages", "Humanities") }
}
data class PersonalTask(val id: String, val subjectId: String, val title: String, val due: String = "", val done: Boolean = false)
data class PersonalMaterial(val id: String, val subjectId: String, val title: String, val text: String = "", val question: String = "", val answer: String = "", val pdf: Boolean = false)
data class PersonalWorkspace(val subjects: List<PersonalSubject> = emptyList(), val tasks: List<PersonalTask> = emptyList(), val materials: List<PersonalMaterial> = emptyList(), val reviewers: List<com.studypath.app.domain.PdfReviewer>? = null)

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
    /** Every screen reads the same UID-owned file after a successful atomic commit. */
    fun observe(): Flow<PersonalWorkspace> = revision.map { read() }.distinctUntilChanged().flowOn(Dispatchers.IO)
    fun update(transform: (PersonalWorkspace) -> PersonalWorkspace): PersonalWorkspace = synchronized(lock) {
        val value = transform(read()); validate(value)
        val bytes = Gson().toJson(value).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_JSON) { "Your personal workspace is full." }
        val temp = File(directory, "workspace.tmp")
        FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        Files.move(temp.toPath(), record.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        revision.value = revision.value + 1
        value
    }
    fun reviewer(id:String) = read().reviewers.orEmpty().firstOrNull { it.materialId==id }
    fun saveReviewer(reviewer:com.studypath.app.domain.PdfReviewer) = update { data ->
        reviewer.validate()
        data.copy(reviewers=data.reviewers.orEmpty().filterNot { it.materialId==reviewer.materialId }+reviewer)
    }
    fun pdf(id: String): File { require(validId(id)); return File(directory, "$id.pdf") }
    fun commitPdf(temp: File, material: PersonalMaterial) = synchronized(lock) {
        require(material.pdf && temp.isFile && temp.length() in 1..MAX_PDF_BYTES)
        require(directory.listFiles().orEmpty().filter { it.extension == "pdf" }.sumOf { it.length() } + temp.length() <= MAX_TOTAL_PDF_BYTES) { "PDF storage is full. Remove an unused PDF first." }
        require(read().subjects.any { it.id == material.subjectId }) { "This subject was removed. Choose another subject." }
        val destination = pdf(material.id)
        require(!destination.exists())
        check(temp.renameTo(destination)) { "Could not save the PDF. Retry the download." }
        try { update { it.copy(materials = it.materials + material) } }
        catch (error: Exception) { destination.delete(); throw error }
    }
    fun removeSubject(id: String) = synchronized(lock) {
        val removed = read().materials.filter { it.subjectId == id && it.pdf }
        update { it.copy(subjects = it.subjects.filterNot { row -> row.id == id }.map { row -> if (id in row.prerequisiteIds.orEmpty()) row.copy(prerequisiteIds = row.prerequisiteIds.orEmpty().filterNot { key -> key == id }) else row }, tasks = it.tasks.filterNot { row -> row.subjectId == id }, materials = it.materials.filterNot { row -> row.subjectId == id }, reviewers=it.reviewers.orEmpty().filterNot { r -> r.materialId in removed.map { m -> m.id } }) }
        removed.forEach { pdf(it.id).delete() }
    }
    fun removeMaterial(id: String) = synchronized(lock) {
        update { it.copy(materials = it.materials.filterNot { row -> row.id == id },reviewers=it.reviewers.orEmpty().filterNot { r -> r.materialId==id }) }; pdf(id).delete()
    }
    companion object {
        private val lock = Any()
        private val revision = MutableStateFlow(0L)
        const val MAX_PDF_BYTES = 25L * 1024 * 1024
        const val MAX_TOTAL_PDF_BYTES = 250L * 1024 * 1024
        private const val MAX_JSON = 3_000_000
        fun newId(): String = UUID.randomUUID().toString()
        private fun validId(value: String) = value.matches(Regex("[a-f0-9-]{36}"))
        fun validate(value: PersonalWorkspace) {
            require(value.subjects.size <= 100 && value.tasks.size <= 500 && value.materials.size <= 200) { "Personal workspace limit reached." }
            val reviewers=value.reviewers.orEmpty()
            require(reviewers.size<=20 && reviewers.map { it.materialId }.distinct().size==reviewers.size) { "Keep at most 20 PDF reviewers." }
            reviewers.forEach { reviewer ->
                reviewer.validate()
                require(value.materials.any { it.id==reviewer.materialId && it.pdf }) { "The reviewer's original PDF must remain in this workspace." }
            }
            val subjects = value.subjects.map { it.id }.toSet()
            require(subjects.size == value.subjects.size && value.tasks.map { it.id }.distinct().size == value.tasks.size && value.materials.map { it.id }.distinct().size == value.materials.size)
            value.subjects.forEach { require(validId(it.id) && it.title.isNotBlank() && it.title.length <= 80) { "Use a subject name of 1–80 characters." } }
            value.subjects.forEach {
                it.gradeDraft?.validate()
                require(it.catalogCourseId == null || it.catalogCourseId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
                require(it.category == null || it.category in PersonalSubject.CATEGORIES) { "Choose a subject category." }
                require(it.code.orEmpty().length <= 24 && it.grade.orEmpty().length <= 16) { "Keep course codes and recorded grades short." }
                require(it.units == null || (it.units.isFinite() && it.units > 0 && it.units <= 30)) { "Units must be greater than 0 and at most 30." }
                val prerequisites = it.prerequisiteIds.orEmpty()
                require(prerequisites.size <= 20 && prerequisites.distinct().size == prerequisites.size && prerequisites.all { id -> id in subjects && id != it.id }) { "Choose other existing subjects as prerequisites." }
            }
            val visiting = mutableSetOf<String>(); val visited = mutableSetOf<String>()
            val byId = value.subjects.associateBy { it.id }
            fun visit(id: String) {
                if (id in visited) return
                require(visiting.add(id)) { "Prerequisites cannot form a circular chain." }
                byId.getValue(id).prerequisiteIds.orEmpty().forEach(::visit)
                visiting.remove(id); visited.add(id)
            }
            subjects.forEach(::visit)
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
