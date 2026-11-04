package com.studypath.app.data

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.security.MessageDigest

data class TextPackageKey(val curriculumVersion: String, val moduleId: String, val version: Int)

data class VerifiedTextPackage(
    val summary: PublishedModuleSummary,
    val manifestJson: String,
    val textJson: String,
    val payload: JsonObject,
    val assets: List<PackageAssetDescriptor> = emptyList(),
    val assetPolicy: PackageAssetPolicy? = null,
) {
    val key get() = TextPackageKey(summary.curriculumVersion, summary.moduleId, summary.version)
}

object TextPackageVerifier {
    /** B10 transport integrity only. B11 lesson/answer semantics require their own validation. */
    fun verify(summary: PublishedModuleSummary, manifestJson: String, textJson: String): VerifiedTextPackage {
        val manifestBytes = TextPackageJson.utf8(manifestJson)
        val textBytes = TextPackageJson.utf8(textJson)
        require(manifestBytes.size.toLong() + textBytes.size < 100_000 && textBytes.isNotEmpty()) { "Text package exceeds its byte limit." }
        require(summary.approved) { "Only approved packages are supported." }
        val manifest = TextPackageJson.objectValue(manifestJson)
        val baseFields = setOf("schemaVersion", "moduleId", "version", "title", "applicability", "source", "review",
            "objectives", "concepts", "lessons", "questions", "text", "assets")
        require(manifest.keySet() == baseFields || manifest.keySet() == baseFields + "assetPolicy") { "Invalid package manifest fields." }
        require(TextPackageJson.integer(manifest["schemaVersion"]) == 1L)
        require(TextPackageJson.string(manifest["moduleId"]) == summary.moduleId &&
            TextPackageJson.integer(manifest["version"]) == summary.version.toLong() &&
            TextPackageJson.string(manifest["title"]) == summary.title) { "Package identity differs from its published summary." }
        val applicability = manifest["applicability"].asJsonObject
        require(applicability.keySet() == setOf("curriculumVersion", "levelId", "subjectId", "classIds"))
        require(applicability["classIds"].isJsonArray)
        val classes = applicability["classIds"].asJsonArray.map { TextPackageJson.string(it).also(DatabaseRecords::requireKey) }
        require(classes.size <= 1000 && classes.toSet().size == classes.size)
        val fields = mapOf("schemaVersion" to 1L, "moduleId" to summary.moduleId, "version" to summary.version,
            "title" to summary.title, "visibility" to "PUBLIC", "textBytes" to summary.textBytes,
            "assetBytes" to summary.assetBytes, "packageSha256" to summary.packageSha256,
            "source" to summary.source, "review" to summary.review,
            "applicability" to buildMap<String, Any> {
                put("curriculumVersion", summary.curriculumVersion); put("levelId", summary.levelId); put("subjectId", summary.subjectId)
                if (summary.classIds.isNotEmpty()) put("classIds", summary.classIds.associateWith { true })
            })
        require(PublishedModuleCatalog.parse(summary.curriculumVersion, summary.moduleId, "v${summary.version}", fields) == summary)
        require(TextPackageJson.string(applicability["curriculumVersion"]) == summary.curriculumVersion &&
            TextPackageJson.string(applicability["levelId"]) == summary.levelId &&
            TextPackageJson.string(applicability["subjectId"]) == summary.subjectId && classes.toSet() == summary.classIds)
        require(manifest["source"] == Gson().toJsonTree(summary.source) && manifest["review"] == Gson().toJsonTree(summary.review)) {
            "Package source or review differs from its published summary."
        }
        listOf("objectives", "concepts", "lessons", "questions").forEach { require(manifest[it].isJsonArray) }
        val (assetPolicy, assets) = CompletePackageAssets.parse(manifest, summary)
        val descriptor = manifest["text"].asJsonObject
        require(descriptor.keySet() == setOf("file", "bytes", "sha256"))
        val file = TextPackageJson.string(descriptor["file"])
        require(file.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}")) && file.endsWith(".json") && file != "manifest.json")
        require(TextPackageJson.integer(descriptor["bytes"]) == textBytes.size.toLong() &&
            TextPackageJson.string(descriptor["sha256"]) == sha(textBytes)) { "Package content length or hash differs." }
        require(summary.textBytes == manifestBytes.size.toLong() + textBytes.size)
        require(assets.none { it.file == file })
        val hashAssets = JsonArray().apply { assets.forEach { asset -> add(JsonObject().apply {
            addProperty("id", asset.id); addProperty("sha256", asset.sha256); addProperty("bytes", asset.bytes)
        }) } }
        val hashInput = "{\"manifestSha256\":\"${sha(manifestBytes)}\",\"textSha256\":\"${sha(textBytes)}\",\"assets\":$hashAssets}"
        require(sha(TextPackageJson.utf8(hashInput)) == summary.packageSha256) { "Package hash differs from its published summary." }
        val payload = TextPackageJson.objectValue(textJson)
        require(TextPackageJson.string(payload["moduleId"]) == summary.moduleId &&
            TextPackageJson.integer(payload["version"]) == summary.version.toLong()) { "Payload identity differs from its manifest." }
        return VerifiedTextPackage(summary, manifestJson, textJson, payload, assets, assetPolicy)
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

/** Reject duplicate JSON members and malformed Unicode before trusting byte/hash comparisons. */
internal object TextPackageJson {
    fun utf8(value: String): ByteArray {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char.isHighSurrogate()) require(index < value.length && value[index++].isLowSurrogate()) { "Unpaired Unicode surrogate." }
            else require(!char.isLowSurrogate()) { "Unpaired Unicode surrogate." }
        }
        return value.toByteArray(Charsets.UTF_8)
    }

    fun objectValue(value: String): JsonObject = try {
        utf8(value)
        JsonReader(StringReader(value)).use { reader ->
            reader.strictness = Strictness.STRICT
            val parsed = element(reader, 0)
            require(parsed.isJsonObject && reader.peek() == JsonToken.END_DOCUMENT) { "Expected one JSON object." }
            parsed.asJsonObject
        }
    } catch (error: IllegalArgumentException) { throw error }
    catch (error: Exception) { throw IllegalArgumentException("Invalid package JSON.", error) }

    fun string(value: JsonElement?): String {
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }

    fun integer(value: JsonElement?): Long {
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return try { value.asBigDecimal.longValueExact() }
        catch (error: ArithmeticException) { throw IllegalArgumentException("Expected an integer.", error) }
    }

    private fun element(reader: JsonReader, depth: Int): JsonElement {
        require(depth < 64) { "Package JSON is nested too deeply." }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().also { result ->
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName().also(::utf8)
                    require(!result.has(name)) { "Duplicate JSON member." }
                    result.add(name, element(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().also { result ->
                reader.beginArray()
                while (reader.hasNext()) result.add(element(reader, depth + 1))
                reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString().also(::utf8))
            JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); com.google.gson.JsonNull.INSTANCE }
            else -> throw IllegalArgumentException("Invalid JSON value.")
        }
    }
}
