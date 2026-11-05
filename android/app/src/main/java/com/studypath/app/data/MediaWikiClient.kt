package com.studypath.app.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ReadingSearchResult(val pageId: Long, val pageKey: String, val title: String, val description: String, val excerpt: String)
data class ReadingContent(
    val pageId: Long, val pageKey: String, val title: String, val excerpt: String,
    val revisionId: Long, val revisionTimestamp: String, val sourceUrl: String, val revisionUrl: String,
    val licenseTitle: String, val licenseUrl: String, val attribution: String,
    val modificationNotice: String, val retrievedAt: Long,
)

sealed class MediaWikiException(message: String) : Exception(message) {
    class InvalidResponse(message: String = "The reading service returned incomplete content.") : MediaWikiException(message)
    class NotFound : MediaWikiException("This reading is no longer available.")
    class RateLimited(val retryAfterMillis: Long) : MediaWikiException("The reading service is busy. Try again later.")
    class HttpFailure(val status: Int) : MediaWikiException("The reading service could not complete the request ($status).")
}

class MediaWikiClient(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val baseUrl: HttpUrl = "https://en.wikipedia.org/w/rest.php/v1/".toHttpUrl(),
    private val now: () -> Long = System::currentTimeMillis,
    private val wait: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    private val requestMutex = Mutex()

    init {
        require(userAgent.isNotBlank()) { "Identify StudyPath with its project contact in User-Agent." }
        require(baseUrl.isHttps || baseUrl.host in setOf("localhost", "127.0.0.1", "::1")) { "The reading service must use HTTPS." }
    }

    /** Called only after explicit user submission. One request, including any retry, runs at a time. */
    suspend fun search(query: String): List<ReadingSearchResult> {
        val submitted = query.trim()
        require(submitted.length in 1..120) { "Enter a search topic between 1 and 120 characters." }
        return requestMutex.withLock {
            val url = baseUrl.newBuilder().addPathSegments("search/page")
                .addQueryParameter("q", submitted).addQueryParameter("limit", "5").build()
            val json = requestJson(url).json
            withContext(Dispatchers.IO) {
                parseContract {
                    val pages = json.getAsJsonArray("pages") ?: invalid()
                    pages.take(5).map { row ->
                        val page = row.asJsonObject
                        ReadingSearchResult(page.positiveId("id"), page.requiredText("key", 240),
                            page.requiredText("title", 240), plainText(page.optionalText("description")).take(500),
                            plainText(page.optionalText("excerpt")).take(1000))
                    }
                }
            }
        }
    }

    suspend fun fetchReading(pageKey: String): ReadingContent = fetchContent(pageKey, false)
    suspend fun fetchFullArticle(pageKey: String): ArticleText {
        val content = fetchContent(pageKey, true)
        return ArticleText(content.title, content.excerpt, content.sourceUrl, content.revisionUrl, content.attribution, content.licenseTitle, content.licenseUrl)
    }
    private suspend fun fetchContent(pageKey: String, full: Boolean): ReadingContent {
        require(pageKey.isNotBlank() && pageKey.length <= 240) { "A valid article key is required." }
        return requestMutex.withLock {
            val url = baseUrl.newBuilder().addPathSegment("page").addPathSegment(pageKey).addPathSegment("with_html").build()
            val receipt = requestJson(url)
            val json = receipt.json
            withContext(Dispatchers.IO) {
                parseContract {
                    val key = json.requiredText("key", 240)
                    val title = json.requiredText("title", 240)
                    val pageId = json.positiveId("id")
                    val latest = json.getAsJsonObject("latest") ?: invalid()
                    val revision = latest.positiveId("id")
                    val timestamp = latest.requiredText("timestamp", 40)
                    if (!timestamp.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"))) invalid()
                    Instant.parse(timestamp)
                    val license = json.getAsJsonObject("license") ?: invalid()
                    val licenseTitle = license.requiredText("title", 80)
                    val licenseUrl = license.requiredText("url", 240)
                    // Match the stored raw URL: URL normalization must not hide credentials, ports or path traversal.
                    if (!LICENSE_URL.matches(licenseUrl)) invalid()
                    val html = json.requiredText("html", MAX_RESPONSE_BYTES)
                    val document = Jsoup.parse(html)
                    val additionalNotices = document.select(".attribution,.license,.licensetpl").eachText()
                        .filter { it.isNotBlank() }.distinct().joinToString(" ")
                    val attribution = "Wikipedia contributors. $title. English Wikipedia." +
                        if (additionalNotices.isEmpty()) "" else " $additionalNotices"
                    if (attribution.length > 500) invalid("Additional attribution cannot fit in an offline snapshot. Open the original source.")
                    val fullText = WikipediaBody.text(document)
                    val excerpt = if (full) fullText else ReadingPages.excerpt(fullText, MAX_EXCERPT_CHARACTERS)
                    if (excerpt.isBlank()) invalid("This article has no suitable text excerpt. Open the original source.")
                    val sourceKey = URLEncoder.encode(key, "UTF-8").replace("+", "%20")
                    ReadingContent(pageId, key, title, excerpt, revision, timestamp,
                        "https://en.wikipedia.org/wiki/$sourceKey", "https://en.wikipedia.org/w/index.php?oldid=$revision",
                        licenseTitle, licenseUrl, attribution,
                        "Text selected, shortened and reformatted for native offline reading. Images and other media omitted.", receipt.receivedAt)
                }
            }
        }
    }

    private data class JsonReceipt(val json: JsonObject, val receivedAt: Long)
    private data class JsonResponse(val code: Int, val retryAfter: String?, val receipt: JsonReceipt?)

    /** The supplied OkHttp cache owns HTTP freshness/validation; a disk hit retains its original receipt time. */
    private suspend fun requestJson(url: HttpUrl): JsonReceipt {
        var retry = 0
        while (true) {
            val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json").get().build()
            val response = client.newCall(request).awaitJsonResponse()
            val retryDelay = when {
                response.code == 404 -> throw MediaWikiException.NotFound()
                response.code == 429 || (response.code == 503 && response.retryAfter != null) -> {
                    val delay = retryAfterMillis(response.retryAfter)
                    if (retry >= MAX_RETRIES || delay > MAX_RETRY_DELAY_MILLIS) throw MediaWikiException.RateLimited(delay)
                    delay
                }
                response.code !in 200..299 -> throw MediaWikiException.HttpFailure(response.code)
                else -> return checkNotNull(response.receipt)
            }
            wait(retryDelay)
            retry++
        }
    }

    /** Keep cancellation attached until the bounded body read finishes on OkHttp's IO callback thread. */
    private suspend fun Call.awaitJsonResponse(): JsonResponse = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) { response.close(); return }
                try {
                    val result = response.use {
                        JsonResponse(it.code, it.header("Retry-After"), if (it.isSuccessful) readJson(it) else null)
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun readJson(response: Response): JsonReceipt = parseContract {
        val body = response.body ?: invalid()
        if (body.contentLength() > MAX_RESPONSE_BYTES) invalid("The article is too large to process safely.")
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        body.byteStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > MAX_RESPONSE_BYTES) invalid("The article is too large to process safely.")
                out.write(buffer, 0, read)
            }
        }
        JsonReceipt(JsonParser.parseString(out.toString("UTF-8")).asJsonObject, response.receivedResponseAtMillis)
    }

    private fun retryAfterMillis(value: String?): Long {
        val seconds = value?.trim()?.toLongOrNull()
        if (seconds != null && seconds >= 0) return seconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
        return runCatching {
            (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now()).coerceAtLeast(0)
        }.getOrDefault(1000)
    }

    private fun plainText(value: String): String {
        val document = Jsoup.parse(value)
        document.select("script,style,form,iframe,object,embed,svg").remove()
        return document.text()
    }

    private fun JsonObject.requiredText(name: String, max: Int): String {
        val value = get(name)
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) invalid()
        return value.asString.also { if (it.isBlank() || it.length > max) invalid() }
    }

    private fun JsonObject.optionalText(name: String): String {
        val value = get(name) ?: return ""
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else ""
    }

    private fun JsonObject.positiveId(name: String): Long {
        val value = get(name)
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) invalid()
        val text = value.asString
        return text.toLongOrNull()?.takeIf { it > 0 && text.matches(Regex("[1-9][0-9]*")) } ?: invalid()
    }

    private inline fun <T> parseContract(block: () -> T): T = try { block() }
        catch (error: MediaWikiException) { throw error }
        catch (_: RuntimeException) { throw MediaWikiException.InvalidResponse() }

    private fun invalid(message: String = "The reading service returned incomplete content."): Nothing =
        throw MediaWikiException.InvalidResponse(message)

    companion object {
        private const val MAX_RESPONSE_BYTES = 2_000_000
        private const val MAX_EXCERPT_CHARACTERS = 8000
        private const val MAX_RETRIES = 1
        private const val MAX_RETRY_DELAY_MILLIS = 10_000L
        private val LICENSE_URL = Regex("https://creativecommons[.]org/licenses/by-sa/[0-9][.][0-9]/(?:deed[.]en)?")
    }
}
