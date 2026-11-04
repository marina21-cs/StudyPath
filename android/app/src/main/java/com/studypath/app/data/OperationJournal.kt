package com.studypath.app.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

enum class OperationSyncState { QUEUED, AWAITING_ACK, CONFIRMED, REJECTED, AUTH_REQUIRED, UNKNOWN_AFTER_RESTART }

data class OperationRecord(val uid: String, val operationId: String, val relativePath: String,
    val payloadSha256: String, val state: OperationSyncState, val createdAt: Long,
    val attemptId: String?, val writerInstanceId: String, val acknowledgedAt: Long?, val verifiedServerTimestamp: Long?)

class OperationCallbackTicket internal constructor(internal val token: SessionToken, internal val record: OperationRecord,
    internal val instanceId: String)

/** Metadata only; the Firebase SDK/domain stores retain payloads. Never infer acknowledgement from connectivity. */
class OperationJournal(root: File, private val sessions: SessionGate) {
    private val root = root.canonicalFile
    private val instanceId = UUID.randomUUID().toString()
    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()
    private val callbacks = mutableMapOf<String, OperationCallbackTicket>()
    private val compactionLock = Any()
    private val changed = MutableStateFlow(0L)
    val changes: StateFlow<Long> = changed

    @Synchronized fun enqueue(token: SessionToken, operationId: String, relativePath: String,
        payloadJson: String, createdAt: Long): OperationRecord {
        sessions.check(token); requireId(operationId); requirePath(relativePath); requireTime(createdAt)
        val fingerprint = payloadFingerprint(payloadJson)
        val old = get(token, operationId)
        if (old != null) {
            require(old.relativePath == relativePath && old.payloadSha256 == fingerprint) {
                "Retry the exact operation payload and path. A deliberate edit needs a new operation identity."
            }
            return old
        }
        require(records(token).size < 5000) { "The operation journal needs reviewed retention; no pending records were discarded." }
        return OperationRecord(token.uid, operationId, relativePath, fingerprint, OperationSyncState.QUEUED,
            createdAt, null, instanceId, null, null).also(::write)
    }

    @Synchronized fun get(token: SessionToken, operationId: String): OperationRecord? {
        sessions.check(token)
        val value = read(token.uid, operationId) ?: return null
        val callback = callbacks[operationId]
        val priorSession = value.state == OperationSyncState.AWAITING_ACK && (callback == null ||
            callback.token.uid != token.uid || callback.token.generation != token.generation)
        if (priorSession || value.writerInstanceId != instanceId && value.state in setOf(OperationSyncState.QUEUED, OperationSyncState.AWAITING_ACK)) {
            callbacks.remove(operationId)
            return value.copy(state = OperationSyncState.UNKNOWN_AFTER_RESTART, writerInstanceId = instanceId).also(::write)
        }
        return value
    }

    @Synchronized fun state(token: SessionToken, operationId: String): OperationSyncState? = get(token, operationId)?.state

    @Synchronized fun records(token: SessionToken): List<OperationRecord> {
        sessions.check(token)
        val directory = file(token.uid, "probe").parentFile!!
        return directory.listFiles().orEmpty().filter { it.name.endsWith(".json") }.map { candidate ->
            val operationId = candidate.name.removeSuffix(".json")
            requireNotNull(get(token, operationId))
        }.sortedWith(compareBy<OperationRecord> { it.createdAt }.thenBy { it.operationId })
    }

    /** Release confirmed payloads before metadata; keep barriers that prevent an old pending edit from replaying. */
    fun compactConfirmed(token: SessionToken, keepRecent: Int = 256,
                         releasePayload: (OperationRecord) -> Unit): Int = synchronized(compactionLock) {
        sessions.check(token)
        require(keepRecent in 0..4999)
        fun removable(): List<OperationRecord> {
            val all = records(token)
            val protected = all.filter { it.state == OperationSyncState.CONFIRMED }.takeLast(keepRecent).map { it.operationId }.toMutableSet()
            all.filter { it.state != OperationSyncState.CONFIRMED }.forEach { pending ->
                all.lastOrNull { overlaps(pending, it) }?.let { protected += it.operationId }
            }
            return all.filter { it.state == OperationSyncState.CONFIRMED && it.operationId !in protected }
        }
        val candidates = synchronized(this) { removable() }
        candidates.forEach { confirmed ->
            sessions.check(token)
            // Payload storage calls journal.get(); never hold the journal monitor across that callback.
            releasePayload(confirmed)
        }
        synchronized(this) {
            sessions.check(token)
            val stillRemovable = removable().toSet()
            var removed = 0
            candidates.filter { it in stillRemovable }.forEach { confirmed ->
                check(file(token.uid, confirmed.operationId).delete()) { "Confirmed receipt cleanup failed; remaining metadata was retained." }
                removed++
                changed.value++
            }
            removed
        }
    }

    @Synchronized fun retry(token: SessionToken, operationId: String, relativePath: String, payloadJson: String): OperationRecord {
        val current = requireNotNull(get(token, operationId)) { "Retain the original operation before retrying." }
        requirePath(relativePath)
        require(current.relativePath == relativePath && current.payloadSha256 == payloadFingerprint(payloadJson)) {
            "The original operation payload changed; retain it and use a new identity for the new edit."
        }
        require(current.state != OperationSyncState.CONFIRMED && current.state != OperationSyncState.AWAITING_ACK) {
            "An acknowledged or active operation cannot be retried as a new edit."
        }
        callbacks.remove(operationId)
        return current.copy(state = OperationSyncState.QUEUED, attemptId = null, writerInstanceId = instanceId,
            acknowledgedAt = null, verifiedServerTimestamp = null).also(::write)
    }

    /** Call immediately before submitting the retained exact payload. Complete this ticket from the actual Task callback. */
    @Synchronized fun awaitingAck(token: SessionToken, operationId: String): OperationCallbackTicket {
        val current = requireNotNull(get(token, operationId))
        require(current.state == OperationSyncState.QUEUED) { "Reconcile or explicitly retry this operation before submitting." }
        val pending = current.copy(state = OperationSyncState.AWAITING_ACK, attemptId = UUID.randomUUID().toString(),
            writerInstanceId = instanceId)
        write(pending)
        return OperationCallbackTicket(token, pending, instanceId).also { callbacks[operationId] = it }
    }

    /** acknowledgedAt is the app's observation of the callback; an independently read server timestamp is separate. */
    @Synchronized fun result(ticket: OperationCallbackTicket, outcome: OperationSyncState, acknowledgedAt: Long? = null,
        verifiedServerTimestamp: Long? = null): Boolean {
        require(outcome in setOf(OperationSyncState.CONFIRMED, OperationSyncState.REJECTED, OperationSyncState.AUTH_REQUIRED))
        if (outcome == OperationSyncState.CONFIRMED) requireTime(requireNotNull(acknowledgedAt))
        else require(acknowledgedAt == null && verifiedServerTimestamp == null)
        verifiedServerTimestamp?.let(::requireTime)
        if (ticket.instanceId != instanceId || callbacks[ticket.record.operationId] !== ticket) return false
        try { sessions.check(ticket.token) } catch (_: IllegalStateException) { callbacks.remove(ticket.record.operationId); return false }
        val current = read(ticket.token.uid, ticket.record.operationId) ?: return false
        if (current != ticket.record || current.writerInstanceId != instanceId || current.state != OperationSyncState.AWAITING_ACK) return false
        write(current.copy(state = outcome, acknowledgedAt = acknowledgedAt, verifiedServerTimestamp = verifiedServerTimestamp))
        callbacks.remove(current.operationId)
        return true
    }

    private fun read(uid: String, operationId: String): OperationRecord? {
        val target = file(uid, operationId)
        if (!target.exists()) return null
        return try {
            require(target.isFile && target.length() in 1..8192)
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val fields = TextPackageJson.objectValue(decoder.decode(ByteBuffer.wrap(target.readBytes())).toString())
            require(fields.keySet() == setOf("schemaVersion", "uid", "operationId", "relativePath", "payloadSha256", "state",
                "createdAt", "attemptId", "writerInstanceId", "acknowledgedAt", "verifiedServerTimestamp"))
            require(TextPackageJson.integer(fields["schemaVersion"]) == 1L)
            val value = OperationRecord(TextPackageJson.string(fields["uid"]), TextPackageJson.string(fields["operationId"]),
                TextPackageJson.string(fields["relativePath"]), TextPackageJson.string(fields["payloadSha256"]),
                OperationSyncState.valueOf(TextPackageJson.string(fields["state"])), TextPackageJson.integer(fields["createdAt"]),
                fields["attemptId"].takeUnless { it.isJsonNull }?.let(TextPackageJson::string),
                TextPackageJson.string(fields["writerInstanceId"]),
                fields["acknowledgedAt"].takeUnless { it.isJsonNull }?.let(TextPackageJson::integer),
                fields["verifiedServerTimestamp"].takeUnless { it.isJsonNull }?.let(TextPackageJson::integer))
            validate(value)
            require(value.uid == uid && value.operationId == operationId)
            value
        } catch (error: Exception) {
            throw IllegalArgumentException("Operation recovery metadata could not be restored. Its local bytes were retained.", error)
        }
    }

    private fun validate(value: OperationRecord) {
        require(value.uid.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        requireId(value.operationId); requirePath(value.relativePath); requireId(value.writerInstanceId); requireTime(value.createdAt)
        require(value.payloadSha256.matches(Regex("[a-f0-9]{64}")))
        value.attemptId?.let(::requireId)
        require(value.state !in setOf(OperationSyncState.AWAITING_ACK, OperationSyncState.CONFIRMED,
            OperationSyncState.REJECTED, OperationSyncState.AUTH_REQUIRED) || value.attemptId != null)
        require((value.state == OperationSyncState.CONFIRMED) == (value.acknowledgedAt != null))
        value.acknowledgedAt?.let(::requireTime)
        require(value.verifiedServerTimestamp == null || value.state == OperationSyncState.CONFIRMED)
        value.verifiedServerTimestamp?.let(::requireTime)
    }

    private fun write(value: OperationRecord) {
        validate(value)
        val target = file(value.uid, value.operationId)
        val directory = target.parentFile!!
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, ".${value.operationId}.${UUID.randomUUID()}.tmp")
        try {
            val fields = gson.toJsonTree(value).asJsonObject.apply { addProperty("schemaVersion", 1) }
            val bytes = TextPackageJson.utf8(gson.toJson(fields)); require(bytes.size <= 8192)
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            changed.value++
        } finally { temporary.delete() }
    }

    private fun file(uid: String, operationId: String): File {
        require(uid.matches(Regex("[A-Za-z0-9_-]{1,128}"))); requireId(operationId)
        val directory = File(root, uid)
        return File(directory, "$operationId.json").also { target ->
            require(!Files.isSymbolicLink(directory.toPath()) && !Files.isSymbolicLink(target.toPath()) &&
                target.canonicalFile == target.absoluteFile && target.toPath().startsWith(root.toPath()))
        }
    }

    companion object {
        fun footprint(record: OperationRecord): String = when (record.relativePath.substringBefore('/')) {
            "saveReading", "deleteReading" -> "savedReadings/" + record.relativePath.substringAfter('/')
            "removePackage" -> "offlinePackages/" + record.relativePath.substringAfter('/')
            "set", "merge" -> record.relativePath.substringAfter('/').takeUnless { it == "_workspace" }.orEmpty()
            else -> record.relativePath
        }
        fun overlaps(first: OperationRecord, second: OperationRecord): Boolean {
            val left = footprint(first); val right = footprint(second)
            return left.isEmpty() || right.isEmpty() || left == right || left.startsWith("$right/") || right.startsWith("$left/")
        }
        private val json = GsonBuilder().disableHtmlEscaping().create()
        private val credentialKeys = setOf("password", "passwd", "token", "idtoken", "accesstoken", "refreshtoken",
            "authtoken", "authorization", "apikey", "secret", "credential", "credentials")
        private fun requireId(value: String) = require(value.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        private fun requireTime(value: Long) = require(value in 1..4_102_444_800_000L)
        private fun normalized(value: String) = value.lowercase(java.util.Locale.ROOT).replace("_", "").replace("-", "")
        private fun requirePath(value: String) {
            val parts = value.split('/')
            require(value.length <= 640 && parts.size in 1..10)
            parts.forEach { requireId(it); require(normalized(it) !in credentialKeys) }
            require(parts.first() !in setOf("users", "catalog", "auth")) { "Journal paths are relative to the UID-owned workspace." }
        }
        private fun canonical(value: JsonElement): String = when {
            value.isJsonNull -> "null"
            value.isJsonObject -> value.asJsonObject.keySet().sorted().joinToString(prefix = "{", postfix = "}") { key ->
                require(normalized(key) !in credentialKeys) { "Credentials cannot enter operation recovery metadata." }
                json.toJson(key) + ":" + canonical(value.asJsonObject[key])
            }
            value.isJsonArray -> value.asJsonArray.joinToString(prefix = "[", postfix = "]") { canonical(it) }
            value.asJsonPrimitive.isNumber -> value.asBigDecimal.let { number ->
                require(number.precision() <= 1000 && number.scale() in -1000..1000)
                number.stripTrailingZeros().toPlainString()
            }
            else -> json.toJson(value)
        }
        fun payloadFingerprint(payloadJson: String): String {
            require(TextPackageJson.utf8(payloadJson).size in 1..1_000_000)
            val wrapper = TextPackageJson.objectValue("{\"payload\":$payloadJson}")
            require(wrapper.keySet() == setOf("payload")) { "Expected one exact JSON payload." }
            return sha256(canonical(wrapper["payload"]))
        }
        /** Value identity is suitable for retries; explicit edits also need a fresh editId, including A→B→A. */
        fun operationIdentity(relativePath: String, payloadJson: String, editId: String? = null): String {
            requirePath(relativePath); editId?.let(::requireId)
            return sha256(relativePath + "\n" + payloadFingerprint(payloadJson) + "\n" + (editId ?: ""))
        }
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(TextPackageJson.utf8(value)).joinToString("") { "%02x".format(it) }
    }
}
