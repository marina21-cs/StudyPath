package com.studypath.app.data

import com.google.gson.JsonObject
import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate

data class PackageAssetDescriptor(
    val id: String, val file: String, val kind: String, val bytes: Long, val sha256: String, val url: String,
)

/** A reviewed manifest's explicit limits, never a default permission to fetch arbitrary hosts. */
data class PackageAssetPolicy(
    val reference: String, val approvalReference: String, val reviewer: String, val reviewedOn: String,
    val origins: Set<String>, val maxCount: Int, val maxFileBytes: Long, val maxTotalBytes: Long,
)

internal object CompletePackageAssets {
    private val filename = Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}")
    private val digest = Regex("[a-f0-9]{64}")
    private val publicHost = Regex("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")

    fun parse(manifest: JsonObject, summary: PublishedModuleSummary): Pair<PackageAssetPolicy?, List<PackageAssetDescriptor>> {
        require(manifest["assets"].isJsonArray)
        val rows = manifest.getAsJsonArray("assets")
        if (rows.size() == 0) {
            require(!manifest.has("assetPolicy") && summary.assetBytes == 0L) { "Empty assets must not declare an asset policy or asset bytes." }
            return null to emptyList()
        }
        val fields = manifest["assetPolicy"]?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalArgumentException("A reviewed asset source and budget policy is required.")
        require(fields.keySet() == setOf("reference", "approvalReference", "reviewer", "reviewedOn", "access", "origins",
            "maxCount", "maxFileBytes", "maxTotalBytes", "mimeTypes")) { "Invalid asset policy fields." }
        fun text(name: String, max: Int = 1000) = TextPackageJson.string(fields[name]).also { require(it.isNotBlank() && it.length <= max) }
        val reference = text("reference"); val approval = text("approvalReference"); val reviewer = text("reviewer")
        require(approval == summary.review["approvalReference"] && text("access") == "PUBLIC_PREPUBLISHED") { "Asset access differs from its reviewed package approval." }
        val reviewedOn = text("reviewedOn", 10)
        require(reviewedOn.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && runCatching { LocalDate.parse(reviewedOn).toString() == reviewedOn }.getOrDefault(false))
        require(fields["origins"].isJsonArray && fields["mimeTypes"].isJsonArray)
        val origins = fields.getAsJsonArray("origins").map { TextPackageJson.string(it).also(::requireOrigin) }
        require(origins.isNotEmpty() && origins.size <= 20 && origins.toSet().size == origins.size)
        require(fields.getAsJsonArray("mimeTypes").map(TextPackageJson::string) == listOf("application/pdf"))
        val maxCount = TextPackageJson.integer(fields["maxCount"]).also { require(it in 1..20) }.toInt()
        val maxFile = TextPackageJson.integer(fields["maxFileBytes"]).also { require(it in 1..20L * 1024 * 1024) }
        val maxTotal = TextPackageJson.integer(fields["maxTotalBytes"]).also { require(it in maxFile..100L * 1024 * 1024) }
        require(rows.size() <= maxCount)
        val assets = rows.map { value ->
            require(value.isJsonObject)
            val row = value.asJsonObject
            require(row.keySet() == setOf("id", "file", "kind", "bytes", "sha256", "url")) { "Invalid asset descriptor fields." }
            val id = TextPackageJson.string(row["id"]).also(DatabaseRecords::requireKey)
            val file = TextPackageJson.string(row["file"])
            require(filename.matches(file) && file.endsWith(".pdf")) { "Invalid PDF filename." }
            require(TextPackageJson.string(row["kind"]) == "PDF") { "Unsupported asset type." }
            val bytes = TextPackageJson.integer(row["bytes"]).also { require(it in 1..maxFile) }
            val sha = TextPackageJson.string(row["sha256"]).also { require(digest.matches(it)) }
            val url = TextPackageJson.string(row["url"])
            requireUrl(url, origins.toSet())
            PackageAssetDescriptor(id, file, "PDF", bytes, sha, url)
        }
        require(assets.map { it.id }.toSet().size == assets.size && assets.map { it.file }.toSet().size == assets.size)
        val total = assets.sumOf { it.bytes }
        require(total <= maxTotal && total == summary.assetBytes) { "Asset sizes exceed the reviewed budget or published inventory." }
        return PackageAssetPolicy(reference, approval, reviewer, reviewedOn, origins.toSet(), maxCount, maxFile, maxTotal) to assets
    }

    fun requireOrigin(value: String) {
        val uri = try { URI(value) } catch (error: Exception) { throw IllegalArgumentException("Invalid asset origin.", error) }
        val host = uri.host.orEmpty()
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.isNullOrEmpty() && publicHost.matches(host) && value == "https://$host" &&
            !host.endsWith(".local") && !host.endsWith(".localhost") && !host.endsWith(".internal") && !host.endsWith(".test") && !host.endsWith(".invalid")) { "Asset origin must be an explicit public HTTPS origin." }
    }

    fun requireUrl(value: String, origins: Set<String>) {
        val uri = try { URI(value) } catch (error: Exception) { throw IllegalArgumentException("Invalid asset URL.", error) }
        require(value.length <= 2048 && uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 && uri.rawFragment == null &&
            "https://${uri.host}" in origins && uri.path.orEmpty().endsWith(".pdf") && !uri.path.orEmpty().split('/').any { it == ".." || it == "." }) { "Asset URL is outside its reviewed public origin." }
    }

    /** Bounded stream checks apply to actual bytes, regardless of Content-Length claims. */
    fun copyAndVerify(input: InputStream, output: java.io.OutputStream, descriptor: PackageAssetDescriptor, checkActive: () -> Unit = {}) {
        val hash = MessageDigest.getInstance("SHA-256")
        val header = java.io.ByteArrayOutputStream(8)
        val tail = ByteArray(1024)
        var tailSize = 0
        var tailCursor = 0
        val buffer = ByteArray(16 * 1024)
        var count = 0L
        while (true) {
            checkActive()
            val length = input.read(buffer)
            if (length < 0) break
            if (length == 0) continue
            count += length
            require(count <= descriptor.bytes) { "Asset exceeds its declared byte count." }
            hash.update(buffer, 0, length)
            header.write(buffer, 0, minOf(8 - header.size(), length))
            if (length >= tail.size) {
                System.arraycopy(buffer, length - tail.size, tail, 0, tail.size); tailSize = tail.size; tailCursor = 0
            } else {
                val first = minOf(length, tail.size - tailCursor)
                System.arraycopy(buffer, 0, tail, tailCursor, first)
                if (length > first) System.arraycopy(buffer, first, tail, 0, length - first)
                tailCursor = (tailCursor + length) % tail.size; tailSize = minOf(tail.size, tailSize + length)
            }
            output.write(buffer, 0, length)
        }
        checkActive()
        require(count == descriptor.bytes && hash.digest().joinToString("") { "%02x".format(it) } == descriptor.sha256) { "Asset length or SHA-256 differs from its manifest." }
        val ending = ByteArray(tailSize) { tail[((if (tailSize == tail.size) tailCursor else 0) + it) % tail.size] }
        require(header.toByteArray().toString(Charsets.US_ASCII).matches(Regex("%PDF-(?:1\\.[0-7]|2\\.0)")) &&
            ending.toString(Charsets.ISO_8859_1).trimEnd().endsWith("%%EOF")) { "Invalid PDF signature or end marker." }
    }

    fun verifyFile(file: File, descriptor: PackageAssetDescriptor) {
        require(file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) && file.length() == descriptor.bytes) { "A required asset is missing or has the wrong length." }
        file.inputStream().use { input -> copyAndVerify(input, object : java.io.OutputStream() { override fun write(value: Int) = Unit; override fun write(value: ByteArray, offset: Int, length: Int) = Unit }, descriptor) }
    }
}
