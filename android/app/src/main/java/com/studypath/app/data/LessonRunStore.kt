package com.studypath.app.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** UID-owned atomic snapshots. Historical runs remain available when a learner deliberately starts again. */
class LessonRunStore(root: File) {
    private val root = root.canonicalFile
    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()
    private data class Entry(val run: LessonRun, val confirmedAt: Long?, val completionSha256: String?)

    @Synchronized fun read(uid: String, module: InteractiveModule, lessonId: String): LessonRun? {
        require(module.lessons.any { it.id == lessonId })
        val pointer = child(lessonDirectory(uid, module, lessonId), "active.json")
        if (!pointer.exists()) {
            val retained = child(lessonDirectory(uid, module, lessonId), "runs").listFiles().orEmpty().any { it.name.endsWith(".json") }
            require(!retained) { "Retained lesson progress has no active pointer; its bytes were preserved." }
            return null
        }
        return checkedRead {
            val fields = objectFile(pointer)
            require(fields.keySet() == setOf("schemaVersion", "uid", "runId") && TextPackageJson.integer(fields["schemaVersion"]) == 1L && TextPackageJson.string(fields["uid"]) == uid)
            val run = requireNotNull(get(uid, module, TextPackageJson.string(fields["runId"]))) { "Active lesson snapshot is missing; retained files were preserved." }
            require(run.lessonId == lessonId)
            run
        }
    }
    @Synchronized fun get(uid: String, module: InteractiveModule, runId: String): LessonRun? {
        DatabaseRecords.requireKey(runId)
        return module.lessons.asSequence().mapNotNull { lesson -> readEntry(uid, module, lesson.id, runId)?.run }.firstOrNull()
    }
    @Synchronized fun recoverAttempt(uid: String, module: InteractiveModule, operation: OperationRecord): LessonRun {
        require(operation.uid == uid) { "The original lesson result belongs to another account." }
        val runId = operation.relativePath.removePrefix("set/lessonAttempts/")
        require(operation.relativePath == "set/lessonAttempts/$runId")
        val run = requireNotNull(get(uid, module, runId)) { "The original lesson result is unavailable. Its recovery data was retained." }
        require(OperationJournal.payloadFingerprint(gson.toJson(LessonEngine.attempt(module, run))) == operation.payloadSha256) {
            "The retained lesson result differs from the original saved change. Both recovery records were kept."
        }
        return run
    }
    @Synchronized fun save(uid: String, module: InteractiveModule, run: LessonRun) {
        LessonEngine.validate(module, run)
        val active = read(uid, module, run.lessonId) // Fail before any write when existing progress cannot be restored.
        val file = snapshot(uid, module, run.lessonId, run.runId)
        val existed = file.exists()
        val current = readEntry(uid, module, run.lessonId, run.runId)
        require(!existed || current != null) { "Existing lesson snapshot is unreadable; its bytes were retained." }
        if (current != null) {
            require(run.revision >= current.run.revision && (run.revision != current.run.revision || run == current.run)) { "A stale lesson save cannot replace committed progress." }
            require(!current.run.completed || current.run == run) { "A completed run is immutable." }
        }
        write(uid, module, Entry(run, current?.confirmedAt, current?.completionSha256))
        // Updating a historical run must not replace the active run selected by Practice again.
        if (!existed || active == null) {
            val pointer = JsonObject().apply { addProperty("schemaVersion", 1); addProperty("uid", uid); addProperty("runId", run.runId) }
            atomic(child(lessonDirectory(uid, module, run.lessonId), "active.json"), gson.toJson(pointer))
        }
    }
    @Synchronized fun confirm(uid: String, module: InteractiveModule, run: LessonRun, confirmedAt: Long) {
        require(confirmedAt in 1..4_102_444_800_000L)
        val digest = completionDigest(module, run)
        val current = requireNotNull(readEntry(uid, module, run.lessonId, run.runId)) { "Persist a completed run before confirming." }
        require(current.run == run)
        if (current.confirmedAt != null) { require(current.completionSha256 == digest); return }
        write(uid, module, Entry(run, confirmedAt, digest))
    }
    @Synchronized fun confirmedAt(uid: String, module: InteractiveModule, run: LessonRun): Long? {
        if (!run.completed) return null
        val current = readEntry(uid, module, run.lessonId, run.runId) ?: return null
        return current.confirmedAt.takeIf { current.run == run && current.completionSha256 == completionDigest(module, run) }
    }
    @Synchronized fun history(uid: String, module: InteractiveModule): List<LessonRun> = entries(uid, module)
        .filter { it.run.completed }.map { it.run }.sortedWith(compareBy<LessonRun> { it.completedAt }.thenBy { it.runId })

    @Synchronized fun pending(uid: String, module: InteractiveModule): List<LessonRun> = entries(uid, module)
        .filter { it.run.completed && it.confirmedAt == null }.map { it.run }.sortedWith(compareBy<LessonRun> { it.startedAt }.thenBy { it.runId })

    private fun entries(uid: String, module: InteractiveModule): List<Entry> = module.lessons.flatMap { lesson ->
        val directory = child(lessonDirectory(uid, module, lesson.id), "runs")
        directory.listFiles().orEmpty().mapNotNull { file ->
            val name = file.name.removeSuffix(".json")
            if (file.name != "$name.json" || !name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) null
            else readEntry(uid, module, lesson.id, name)
        }
    }

    private fun readEntry(uid: String, module: InteractiveModule, lessonId: String, runId: String): Entry? {
        val file = snapshot(uid, module, lessonId, runId)
        if (!file.exists()) return null
        return checkedRead {
            val fields = objectFile(file)
            require(fields.keySet() == setOf("schemaVersion", "uid", "run", "confirmedAt", "completionSha256"))
            require(TextPackageJson.integer(fields["schemaVersion"]) == 1L && TextPackageJson.string(fields["uid"]) == uid)
            val objectRun = fields["run"].asJsonObject
            require(objectRun.keySet() == setOf("runId", "curriculumVersion", "moduleId", "version", "packageSha256", "lessonId", "startedAt", "updatedAt", "position", "answers", "completedAt", "revision"))
            listOf("runId", "curriculumVersion", "moduleId", "packageSha256", "lessonId").forEach { TextPackageJson.string(objectRun[it]) }
            require(TextPackageJson.integer(objectRun["version"]) in 1..1_000_000)
            require(TextPackageJson.integer(objectRun["position"]) in 0..1000)
            require(TextPackageJson.integer(objectRun["revision"]) in 0..1_000_000)
            listOf("startedAt", "updatedAt").forEach { require(TextPackageJson.integer(objectRun[it]) in 1..4_102_444_800_000L) }
            if (!objectRun["completedAt"].isJsonNull) require(TextPackageJson.integer(objectRun["completedAt"]) in 1..4_102_444_800_000L)
            objectRun["answers"].asJsonObject.entrySet().forEach { (_, value) ->
                val a = value.asJsonObject
                require(a.keySet() == setOf("draft", "firstResponse", "status", "wrongCount", "hintUsed", "assisted"))
                require(TextPackageJson.integer(a["wrongCount"]) in 0..10000)
                require(TextPackageJson.string(a["status"]) in LessonAnswerStatus.entries.map { it.name })
                listOf("hintUsed", "assisted").forEach { require(a[it].isJsonPrimitive && a[it].asJsonPrimitive.isBoolean) }
                listOf("draft", "firstResponse").forEach { key ->
                    if (key == "draft" || !a[key].isJsonNull) { require(a[key].isJsonArray); a[key].asJsonArray.forEach(TextPackageJson::string) }
                }
            }
            val run = gson.fromJson(objectRun, LessonRun::class.java)
            LessonEngine.validate(module, run)
            require(run.runId == runId && run.lessonId == lessonId)
            val confirmedAt = fields["confirmedAt"].takeUnless { it.isJsonNull }?.let(TextPackageJson::integer)
            val digest = fields["completionSha256"].takeUnless { it.isJsonNull }?.let(TextPackageJson::string)
            require((confirmedAt == null) == (digest == null))
            require(confirmedAt == null || confirmedAt in 1..4_102_444_800_000L && digest == completionDigest(module, run))
            Entry(run, confirmedAt, digest)
        }
    }
    private fun <T> checkedRead(block: () -> T): T = try { block() }
        catch (error: Exception) { throw IllegalArgumentException("Saved lesson progress could not be restored. Its files were retained; retry after recovery.", error) }

    private fun write(uid: String, module: InteractiveModule, entry: Entry) {
        val value = JsonObject().apply {
            addProperty("schemaVersion", 1); addProperty("uid", uid); add("run", gson.toJsonTree(entry.run))
            if (entry.confirmedAt == null) add("confirmedAt", JsonNull.INSTANCE) else addProperty("confirmedAt", entry.confirmedAt)
            if (entry.completionSha256 == null) add("completionSha256", JsonNull.INSTANCE) else addProperty("completionSha256", entry.completionSha256)
        }
        atomic(snapshot(uid, module, entry.run.lessonId, entry.run.runId), gson.toJson(value))
    }
    private fun completionDigest(module: InteractiveModule, run: LessonRun) = MessageDigest.getInstance("SHA-256")
        .digest(TextPackageJson.utf8(gson.toJson(LessonEngine.attempt(module, run)))).joinToString("") { "%02x".format(it) }
    private fun objectFile(file: File): JsonObject {
        require(file.isFile && file.length() in 1..200_000)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return TextPackageJson.objectValue(decoder.decode(ByteBuffer.wrap(file.readBytes())).toString())
    }
    private fun lessonDirectory(uid: String, module: InteractiveModule, lessonId: String): File {
        require(uid.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        listOf(module.summary.curriculumVersion, module.summary.moduleId, lessonId).forEach(DatabaseRecords::requireKey)
        require(module.summary.version in 1..1_000_000 && module.lessons.any { it.id == lessonId })
        return child(child(child(child(child(root, uid), module.summary.curriculumVersion), module.summary.moduleId), "v${module.summary.version}"), lessonId)
    }
    private fun snapshot(uid: String, module: InteractiveModule, lessonId: String, runId: String): File {
        DatabaseRecords.requireKey(runId)
        return child(child(lessonDirectory(uid, module, lessonId), "runs"), "$runId.json")
    }
    private fun child(parent: File, name: String): File = File(parent, name).also {
        require(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile == it.absoluteFile && it.canonicalFile.toPath().startsWith(root.toPath())) { "Lesson storage path escapes app-private storage." }
    }
    private fun atomic(file: File, text: String) {
        val directory = requireNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs())
        require(file.canonicalFile == file.absoluteFile && !Files.isSymbolicLink(file.toPath()) && file.canonicalFile.toPath().startsWith(root.toPath()))
        val temporary = File(directory, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { out -> out.write(TextPackageJson.utf8(text)); out.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
