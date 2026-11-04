package com.studypath.app.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest

/** Fabricated approval/source claims are test data, never published content. */
internal object TextPackageFixture {
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/$name")).bufferedReader().use { it.readText() }
    val manifestJson get() = resource("text-package-manifest.json")
    val textJson get() = resource("text-package-content.json")
    val summary: PublishedModuleSummary get() {
        @Suppress("UNCHECKED_CAST")
        val fields = Gson().fromJson(resource("text-package-summary.json"), Map::class.java) as Map<String, Any?>
        return PublishedModuleCatalog.parse("fixture_v1", "fixture_module", "v1", fields)
    }
    fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun pair(version: Int = 1, teaching: String? = null): Pair<PublishedModuleSummary, Pair<String, String>> {
        val text = JsonParser.parseString(textJson).asJsonObject.apply {
            addProperty("version", version)
            if (teaching != null) addProperty("teaching", teaching)
        }.toString()
        val manifest = JsonParser.parseString(manifestJson).asJsonObject.apply {
            addProperty("version", version)
            getAsJsonObject("text").apply {
                addProperty("bytes", text.toByteArray(Charsets.UTF_8).size)
                addProperty("sha256", sha(text))
            }
        }.toString()
        return bind(summary.copy(version = version), manifest, text) to (manifest to text)
    }
    fun bind(summary: PublishedModuleSummary, manifest: String, text: String): PublishedModuleSummary {
        val hashInput = JsonObject().apply {
            addProperty("manifestSha256", sha(manifest)); addProperty("textSha256", sha(text))
            add("assets", com.google.gson.JsonArray())
        }.toString()
        return summary.copy(textBytes = (manifest.toByteArray(Charsets.UTF_8).size + text.toByteArray(Charsets.UTF_8).size).toLong(), packageSha256 = sha(hashInput))
    }
    fun verified(version: Int = 1): VerifiedTextPackage {
        val (summary, bytes) = pair(version)
        return TextPackageVerifier.verify(summary, bytes.first, bytes.second)
    }
}
