package com.studypath.app.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

enum class TextPackageState { PENDING, READY, REMOVING }

data class StoredTextPackage(
    val verified: VerifiedTextPackage,
    val downloadedAt: Long,
    val confirmedAt: Long?,
    val state: TextPackageState,
) {
    val key get() = verified.key
    val ready get() = state == TextPackageState.READY
}

/** App-private byte retention; only the caller's server-acknowledged path may call confirm. */
class TextPackageStore(
    root: File,
    private val validatePdf: (File) -> Unit = NativePdfAssetValidator::validate,
    private val availableBytes: (File) -> Long = { it.usableSpace },
) {
    private val root = root.canonicalFile
    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()
    private val maxTimestamp = 4_102_444_800_000L

    @Synchronized fun stage(uid: String, verified: VerifiedTextPackage, downloadedAt: Long = System.currentTimeMillis(), assets: Map<String, File> = emptyMap()): StoredTextPackage {
        require(downloadedAt in 1..maxTimestamp)
        val checked = TextPackageVerifier.verify(verified.summary, verified.manifestJson, verified.textJson)
        val key = checked.key
        val current = read(uid, key.curriculumVersion, key.moduleId, key.version)
        if (current != null) {
            require(current.verified.summary.packageSha256 == checked.summary.packageSha256) { "An immutable version already has different bytes." }
            return current
        }
        require(assets.keys == checked.assets.map { it.id }.toSet()) { "Every required asset must be fully downloaded before staging." }
        if (checked.assets.isNotEmpty()) stageAssets(uid, checked, assets)
        return StoredTextPackage(checked, downloadedAt, null, TextPackageState.PENDING).also { write(uid, it) }
    }

    @Synchronized fun confirm(uid: String, verified: VerifiedTextPackage, confirmedAt: Long): StoredTextPackage {
        val checked = TextPackageVerifier.verify(verified.summary, verified.manifestJson, verified.textJson)
        val key = checked.key
        val current = requireNotNull(read(uid, key.curriculumVersion, key.moduleId, key.version)) { "Stage complete validated bytes before confirming." }
        require(current.verified.summary.packageSha256 == checked.summary.packageSha256)
        // downloadedAt comes from the device clock; confirmedAt comes from the server clock.
        require(confirmedAt in 1..maxTimestamp)
        if (current.ready) return current
        return current.copy(confirmedAt = confirmedAt, state = TextPackageState.READY).also { write(uid, it) }
    }

    @Synchronized fun read(uid: String, curriculum: String, moduleId: String, version: Int): StoredTextPackage? {
        val key = TextPackageKey(curriculum, moduleId, version)
        val file = entry(uid, key)
        if (!file.exists()) return null
        return runCatching {
            require(file.isFile && file.length() in 1..1_000_000)
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val fields = TextPackageJson.objectValue(decoder.decode(ByteBuffer.wrap(file.readBytes())).toString())
            require(fields.keySet() == setOf("schemaVersion", "uid", "summary", "manifestJson", "textJson", "downloadedAt", "confirmedAt"))
            require(TextPackageJson.integer(fields["schemaVersion"]) == 1L && TextPackageJson.string(fields["uid"]) == uid)
            val summary = gson.fromJson(fields["summary"], PublishedModuleSummary::class.java)
            val verified = TextPackageVerifier.verify(summary, TextPackageJson.string(fields["manifestJson"]), TextPackageJson.string(fields["textJson"]))
            require(verified.key == key)
            verified.assets.forEach { descriptor ->
                val asset = safeChild(assetDirectory(uid, key), descriptor.file)
                CompletePackageAssets.verifyFile(asset, descriptor)
                validatePdf(asset)
            }
            val downloadedAt = TextPackageJson.integer(fields["downloadedAt"])
            require(downloadedAt in 1..maxTimestamp)
            val confirmedAt = fields["confirmedAt"].takeUnless { it.isJsonNull }?.let(TextPackageJson::integer)
            require(confirmedAt == null || confirmedAt in 1..maxTimestamp)
            StoredTextPackage(verified, downloadedAt, confirmedAt, if (isRemoving(uid, key)) TextPackageState.REMOVING
                else if (confirmedAt == null) TextPackageState.PENDING else TextPackageState.READY)
        }.getOrNull()
    }

    @Synchronized fun list(uid: String): List<StoredTextPackage> {
        val directory = userDirectory(uid)
        if (!directory.exists()) return emptyList()
        return directory.listFiles().orEmpty().filter { it.isDirectory && validKey(it.name) && !Files.isSymbolicLink(it.toPath()) }
            .flatMap { curriculum -> curriculum.listFiles().orEmpty().filter { it.isDirectory && validKey(it.name) && !Files.isSymbolicLink(it.toPath()) }
                .flatMap { module -> module.listFiles().orEmpty().mapNotNull { file ->
                    val match = Regex("v([1-9][0-9]{0,6})\\.json").matchEntire(file.name) ?: return@mapNotNull null
                    val version = match.groupValues[1].toInt()
                    if (version > 1_000_000 || Files.isSymbolicLink(file.toPath())) null else read(uid, curriculum.name, module.name, version)
                } }
            }.sortedWith(compareBy<StoredTextPackage> { it.key.curriculumVersion }.thenBy { it.key.moduleId }.thenByDescending { it.key.version })
    }

    @Synchronized fun remove(uid: String, key: TextPackageKey) {
        val file = entry(uid, key)
        check(!file.exists() || file.delete()) { "Could not remove selected text package." }
        val assets = assetDirectory(uid, key)
        check(!assets.exists() || assets.deleteRecursively()) { "Could not remove selected package assets." }
        cancelRemoval(uid, key)
    }

    /** The marker check and deletion share one monitor so a late finisher cannot remove a replacement. */
    @Synchronized fun finishRemoval(uid: String, key: TextPackageKey, operationId: String): Boolean {
        DatabaseRecords.requireKey(operationId)
        if (removalOperationId(uid, key) != operationId) return false
        remove(uid, key)
        return true
    }

    @Synchronized fun beginRemoval(uid: String, key: TextPackageKey, operationId: String = "manual") {
        DatabaseRecords.requireKey(operationId)
        if (isRemoving(uid, key)) { require(removalOperationId(uid, key) == operationId); return }
        val retained = requireNotNull(read(uid, key.curriculumVersion, key.moduleId, key.version)) { "The retained download is unavailable." }
        val marker = removalMarker(uid, key)
        val temporary = File(marker.parentFile, ".${marker.name}.${UUID.randomUUID()}.tmp")
        val value = JsonObject().apply {
            addProperty("uid", uid); addProperty("packageSha256", retained.verified.summary.packageSha256); addProperty("operationId", operationId)
        }
        try {
            FileOutputStream(temporary).use { it.write(TextPackageJson.utf8(value.toString())); it.fd.sync() }
            Files.move(temporary.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    @Synchronized fun isRemoving(uid: String, key: TextPackageKey): Boolean {
        val marker = removalMarker(uid, key)
        if (!marker.exists()) return false
        require(marker.isFile && marker.length() in 1..512) { "Download removal recovery needs review; its bytes were retained." }
        val fields = TextPackageJson.objectValue(marker.readText(Charsets.UTF_8))
        require(fields.keySet() == setOf("uid", "packageSha256", "operationId") && TextPackageJson.string(fields["uid"]) == uid &&
            TextPackageJson.string(fields["packageSha256"]).matches(Regex("[a-f0-9]{64}")))
        DatabaseRecords.requireKey(TextPackageJson.string(fields["operationId"]))
        return true
    }

    @Synchronized fun removalOperationId(uid: String, key: TextPackageKey): String? =
        if (!isRemoving(uid, key)) null else TextPackageJson.string(TextPackageJson.objectValue(removalMarker(uid, key).readText())["operationId"])

    @Synchronized fun cancelRemoval(uid: String, key: TextPackageKey) {
        val marker = removalMarker(uid, key)
        check(!marker.exists() || marker.delete()) { "Download removal recovery could not be updated." }
    }

    private fun removalMarker(uid: String, key: TextPackageKey) = safeChild(requireNotNull(entry(uid, key).parentFile), "v${key.version}.removing")

    /** Return a file only for a fully revalidated, acknowledged package owned by this UID. */
    @Synchronized fun assetFile(uid: String, key: TextPackageKey, assetId: String): File? {
        DatabaseRecords.requireKey(assetId)
        val retained = read(uid, key.curriculumVersion, key.moduleId, key.version)?.takeIf { it.ready } ?: return null
        val asset = retained.verified.assets.singleOrNull { it.id == assetId } ?: return null
        return safeChild(assetDirectory(uid, key), asset.file)
    }

    private fun stageAssets(uid: String, verified: VerifiedTextPackage, source: Map<String, File>) {
        val destination = assetDirectory(uid, verified.key)
        val parent = requireNotNull(destination.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Could not create package asset storage." }
        require(assetDirectory(uid, verified.key) == destination)
        require(availableBytes(parent) >= verified.summary.assetBytes + 16_384L) { "Not enough storage for the complete package." }
        val temporary = safeChild(parent, ".v${verified.key.version}.${UUID.randomUUID()}.assets.tmp")
        check(temporary.mkdir()) { "Could not stage package assets." }
        try {
            verified.assets.forEach { descriptor ->
                val input = requireNotNull(source[descriptor.id])
                require(input.isFile && !Files.isSymbolicLink(input.toPath()))
                val output = safeChild(temporary, descriptor.file)
                FileOutputStream(output).use { stream ->
                    input.inputStream().use { CompletePackageAssets.copyAndVerify(it, stream, descriptor) }
                    stream.fd.sync()
                }
                validatePdf(output)
            }
            // No metadata is published until every staged file passes. Earlier versions have separate directories.
            if (destination.exists()) check(destination.deleteRecursively()) { "Could not replace damaged assets." }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.deleteRecursively() }
    }

    private fun write(uid: String, value: StoredTextPackage) {
        val file = entry(uid, value.key)
        val directory = requireNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Could not create text package storage." }
        // Recheck after directory creation so a symlink never redirects a package write.
        require(entry(uid, value.key) == file)
        val fields = JsonObject().apply {
            addProperty("schemaVersion", 1); addProperty("uid", uid); add("summary", gson.toJsonTree(value.verified.summary))
            addProperty("manifestJson", value.verified.manifestJson); addProperty("textJson", value.verified.textJson)
            addProperty("downloadedAt", value.downloadedAt)
            if (value.confirmedAt == null) add("confirmedAt", com.google.gson.JsonNull.INSTANCE) else addProperty("confirmedAt", value.confirmedAt)
        }
        val temporary = File(directory, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output -> output.write(TextPackageJson.utf8(gson.toJson(fields))); output.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private fun userDirectory(uid: String): File {
        require(uid.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid account storage key." }
        return safeChild(root, uid)
    }

    private fun entry(uid: String, key: TextPackageKey): File {
        require(validKey(key.curriculumVersion) && validKey(key.moduleId) && key.version in 1..1_000_000) { "Invalid package storage key." }
        return safeChild(safeChild(safeChild(userDirectory(uid), key.curriculumVersion), key.moduleId), "v${key.version}.json")
    }

    private fun assetDirectory(uid: String, key: TextPackageKey): File {
        val file = entry(uid, key)
        return safeChild(requireNotNull(file.parentFile), "v${key.version}-assets")
    }

    private fun safeChild(parent: File, name: String): File = File(parent, name).also {
        require(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile == it.absoluteFile && it.canonicalFile.toPath().startsWith(root.toPath())) {
            "Text package path escapes app-private storage."
        }
    }

    private fun validKey(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,64}"))
}
