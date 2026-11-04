package com.studypath.app.data

import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Private study-record JSON retained across server rejection; credentials are rejected by the journal codec. */
class OperationPayloadStore(root: File, private val sessions: SessionGate, private val journal: OperationJournal,
    private val byteBudget: Long = 16L * 1024 * 1024) {
    private val root = root.canonicalFile
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    init { require(byteBudget in 1..16L * 1024 * 1024) }

    @Synchronized fun retain(token: SessionToken, operationId: String, relativePath: String, payloadJson: String) {
        sessions.check(token)
        val fingerprint = OperationJournal.payloadFingerprint(payloadJson)
        val record = requireNotNull(journal.get(token, operationId)) { "Retain operation metadata before retaining its payload." }
        require(record.relativePath == relativePath && record.payloadSha256 == fingerprint && record.state != OperationSyncState.CONFIRMED) {
            "Retain only the original unconfirmed operation payload and path. A new edit needs a new identity."
        }
        val target = file(token, operationId)
        if (target.exists()) {
            requireNotNull(read(token, operationId, relativePath)) // Validate before an exact retry; preserve its original JSON bytes.
            return
        }
        val fields = mapOf("schemaVersion" to 1, "uid" to token.uid, "operationId" to operationId,
            "relativePath" to relativePath, "payloadSha256" to fingerprint, "payloadJson" to payloadJson)
        val bytes = TextPackageJson.utf8(gson.toJson(fields))
        require(bytes.size <= MAX_FILE_BYTES)
        val directory = target.parentFile!!
        val currentBytes = directory.listFiles().orEmpty().sumOf { retained ->
            require(retained.isFile && !Files.isSymbolicLink(retained.toPath())) { "Retained operation payload storage needs recovery." }
            retained.length().also { require(it in 0..byteBudget) }
        }
        require(currentBytes <= byteBudget - bytes.size) { "Private operation payload storage is full; existing pending work was retained." }
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, ".$operationId.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    @Synchronized fun read(token: SessionToken, operationId: String, relativePath: String): String? {
        sessions.check(token)
        val record = requireNotNull(journal.get(token, operationId)) { "Original operation recovery metadata is missing." }
        require(record.relativePath == relativePath) { "The requested payload path does not match the original operation." }
        val target = file(token, operationId)
        if (!target.exists()) return null
        return try {
            require(target.isFile && target.length() in 1..MAX_FILE_BYTES.toLong())
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val fields = TextPackageJson.objectValue(decoder.decode(ByteBuffer.wrap(target.readBytes())).toString())
            require(fields.keySet() == setOf("schemaVersion", "uid", "operationId", "relativePath", "payloadSha256", "payloadJson"))
            require(TextPackageJson.integer(fields["schemaVersion"]) == 1L && TextPackageJson.string(fields["uid"]) == token.uid &&
                TextPackageJson.string(fields["operationId"]) == operationId && TextPackageJson.string(fields["relativePath"]) == relativePath)
            val payload = TextPackageJson.string(fields["payloadJson"])
            val fingerprint = OperationJournal.payloadFingerprint(payload)
            require(fingerprint == record.payloadSha256 && fingerprint == TextPackageJson.string(fields["payloadSha256"]))
            payload
        } catch (error: Exception) {
            throw IllegalArgumentException("Private operation payload could not be restored. Its local file was retained.", error)
        }
    }

    @Synchronized fun removeConfirmed(token: SessionToken, record: OperationRecord) {
        sessions.check(token)
        val authoritative = requireNotNull(journal.get(token, record.operationId))
        require(authoritative == record && authoritative.state == OperationSyncState.CONFIRMED && record.uid == token.uid) {
            "Only an authoritative acknowledged operation may release its retained payload."
        }
        if (read(token, record.operationId, record.relativePath) == null) return
        check(file(token, record.operationId).delete()) { "The acknowledged payload could not be removed; its receipt was retained." }
    }

    private fun file(token: SessionToken, operationId: String): File {
        require(operationId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val directory = File(root, token.uid)
        return File(directory, "$operationId.json").also { target ->
            require(!Files.isSymbolicLink(directory.toPath()) && !Files.isSymbolicLink(target.toPath()) &&
                target.canonicalFile == target.absoluteFile && target.toPath().startsWith(root.toPath()))
        }
    }

    private companion object { const val MAX_FILE_BYTES = 2_100_000 }
}
