package com.studypath.app.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Synthetic origin/review data only; no production source or approval is established here. */
internal object CompletePackageFixture {
    val pdf: ByteArray get() {
        val content = "BT /F1 12 Tf 10 100 Td (Synthetic test PDF) Tj ET\n"
        val objects = listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 240 240] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${content.toByteArray().size} >>\nstream\n${content}endstream", "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        val result = StringBuilder("%PDF-1.4\n"); val offsets = mutableListOf<Int>()
        objects.forEachIndexed { index, value -> offsets.add(result.toString().toByteArray().size); result.append("${index + 1} 0 obj\n$value\nendobj\n") }
        val xref = result.toString().toByteArray().size
        result.append("xref\n0 6\n0000000000 65535 f \n")
        offsets.forEach { result.append("%010d 00000 n \n".format(it)) }
        result.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return result.toString().toByteArray()
    }
    fun candidate(version: Int = 1, change: (JsonObject) -> Unit = {}): Triple<PublishedModuleSummary, String, String> {
        val (base, pair) = TextPackageFixture.pair(version)
        val manifest = JsonParser.parseString(pair.first).asJsonObject.apply {
            add("assetPolicy", JsonObject().apply {
                addProperty("reference", "SYNTHETIC TEST POLICY")
                addProperty("approvalReference", base.review.getValue("approvalReference").toString())
                addProperty("reviewer", "TEST ONLY"); addProperty("reviewedOn", "2026-10-30")
                addProperty("access", "PUBLIC_PREPUBLISHED")
                add("origins", JsonArray().apply { add("https://assets.example.org") })
                addProperty("maxCount", 2); addProperty("maxFileBytes", 4096); addProperty("maxTotalBytes", 8192)
                add("mimeTypes", JsonArray().apply { add("application/pdf") })
            })
            add("assets", JsonArray().apply { add(JsonObject().apply {
                addProperty("id", "source_pdf"); addProperty("file", "source.pdf"); addProperty("kind", "PDF")
                addProperty("bytes", pdf.size); addProperty("sha256", TextPackageFixture.sha(pdf.toString(Charsets.UTF_8)))
                addProperty("url", "https://assets.example.org/source.pdf")
            }) })
            change(this)
        }.toString()
        val manifestObject = JsonParser.parseString(manifest).asJsonObject
        val assets = manifestObject.getAsJsonArray("assets")
        val hashInput = JsonObject().apply {
            addProperty("manifestSha256", TextPackageFixture.sha(manifest)); addProperty("textSha256", TextPackageFixture.sha(pair.second))
            add("assets", JsonArray().apply { assets.forEach { value -> add(JsonObject().apply {
                add("id", value.asJsonObject["id"]); add("sha256", value.asJsonObject["sha256"]); add("bytes", value.asJsonObject["bytes"])
            }) } })
        }.toString()
        return Triple(base.copy(textBytes = manifest.toByteArray().size.toLong() + pair.second.toByteArray().size,
            assetBytes = assets.sumOf { it.asJsonObject["bytes"].asLong }, packageSha256 = TextPackageFixture.sha(hashInput)), manifest, pair.second)
    }
}
