package com.studypath.app.data

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

data class ReadingStarter(
    val id: String, val pageId: Long, val pageKey: String, val title: String,
    val description: String, val topicQuery: String, val revisionId: Long,
    val revisionTimestamp: String, val sourceUrl: String, val revisionUrl: String,
    val licenseTitle: String, val licenseUrl: String, val attribution: String, val gatheredAt: Long,
)

object ReadingStarterRecords {
    /** Gathered discovery metadata is not a lesson, saved excerpt or curriculum approval. */
    fun parse(id: String, fields: Map<String, Any?>): ReadingStarter {
        DatabaseRecords.requireKey(id)
        require(fields.keys == setOf("schemaVersion", "kind", "provider", "pageId", "pageKey", "title", "description", "topicQuery",
            "revisionId", "revisionTimestamp", "sourceUrl", "revisionUrl", "licenseTitle", "licenseUrl", "attribution", "retrievedAt"))
        require(integer(fields["schemaVersion"], 1, 1) == 1L && fields["kind"] == "SUPPLEMENTAL" && fields["provider"] == "ENGLISH_WIKIPEDIA")
        val pageId = integer(fields["pageId"], 1)
        val revision = integer(fields["revisionId"], 1)
        require(id == "wiki_${pageId}_$revision")
        val pageKey = text(fields["pageKey"], 240)
        require(StandardCharsets.UTF_8.newEncoder().canEncode(pageKey))
        val sourceUrl = "https://en.wikipedia.org/wiki/" + URLEncoder.encode(pageKey, "UTF-8").replace("+", "%20")
        val revisionUrl = "https://en.wikipedia.org/w/index.php?oldid=$revision"
        require(fields["sourceUrl"] == sourceUrl && fields["revisionUrl"] == revisionUrl)
        val timestamp = text(fields["revisionTimestamp"], 20)
        require(timestamp.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")) &&
            runCatching { Instant.parse(timestamp).toString() == timestamp }.getOrDefault(false))
        val license = text(fields["licenseUrl"], 100)
        require(license.matches(Regex("https://creativecommons\\.org/licenses/by-sa/\\d\\.\\d/(deed\\.en)?")))
        return ReadingStarter(id, pageId, pageKey, text(fields["title"], 240), text(fields["description"], 500),
            text(fields["topicQuery"], 120), revision, timestamp, sourceUrl, revisionUrl,
            text(fields["licenseTitle"], 80), license, text(fields["attribution"], 500),
            integer(fields["retrievedAt"], 0, 4_102_444_800_000))
    }

    private fun text(value: Any?, max: Int): String {
        require(value is String && value.isNotBlank() && value.length <= max)
        return value
    }
    private fun integer(value: Any?, min: Long, max: Long = 9_007_199_254_740_991): Long {
        require(value is Number)
        val number = value.toDouble()
        require(number.isFinite() && number >= min.toDouble() && number <= max.toDouble() && number % 1.0 == 0.0)
        return number.toLong()
    }
}
