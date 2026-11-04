package com.studypath.app.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class MediaWikiClientTest {
    private lateinit var server: MockWebServer
    private val waits = mutableListOf<Long>()
    private lateinit var api: MediaWikiClient

    @Before fun start() {
        server = MockWebServer()
        server.start()
        api = MediaWikiClient(
            client = OkHttpClient(),
            userAgent = "StudyPath-test/1.0 (test fixture)",
            baseUrl = server.url("/w/rest.php/v1/"),
            now = { 1_700_000_000_000 },
            wait = { waits += it },
        )
    }

    @After fun stop() = server.shutdown()

    @Test fun `submitted search is encoded and snippets become inert native text`() = runBlocking<Unit> {
        server.enqueue(json("""{"pages":[{"id":8519,"key":"Data_structure","title":"Data structure","description":"A & B","excerpt":"A <span class='searchmatch'>structure</span> &amp; collection<script>bad()</script>"}]}"""))
        val result = api.search("data & structure")
        assertEquals("Data_structure", result.single().pageKey)
        assertEquals("A structure & collection", result.single().excerpt)
        val request = server.takeRequest()
        assertEquals("data & structure", request.requestUrl!!.queryParameter("q"))
        assertEquals("5", request.requestUrl!!.queryParameter("limit"))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("StudyPath-test"))
    }

    @Test fun `reading retains provenance while removing active markup and media`() = runBlocking<Unit> {
        val requestStartedAt = System.currentTimeMillis()
        server.enqueue(json(page("<h2>Overview</h2><p>A useful data structure.</p><script>steal()</script><form>secret form</form><iframe>remote frame</iframe><video>video text</video><p>Second paragraph.</p>")))
        val reading = api.fetchReading("Data_structure")
        assertEquals(8519L, reading.pageId)
        assertEquals("Data_structure", reading.pageKey)
        assertEquals(1377666699L, reading.revisionId)
        assertEquals("2026-09-30T12:52:14Z", reading.revisionTimestamp)
        assertEquals("https://en.wikipedia.org/w/index.php?oldid=1377666699", reading.revisionUrl)
        assertEquals("https://creativecommons.org/licenses/by-sa/4.0/deed.en", reading.licenseUrl)
        assertTrue(reading.attribution.contains("Wikipedia contributors"))
        assertTrue(reading.modificationNotice.contains("selected"))
        assertTrue(reading.retrievedAt in requestStartedAt..System.currentTimeMillis())
        assertTrue(reading.excerpt.contains("A useful data structure."))
        assertFalse(reading.excerpt.contains("steal"))
        assertFalse(reading.excerpt.contains("secret form"))
        assertFalse(reading.excerpt.contains("remote frame"))
        assertFalse(reading.excerpt.contains("video text"))
    }

    @Test fun `excerpts stay within the database budget`() = runBlocking<Unit> {
        server.enqueue(json(page("<p>${"long useful text ".repeat(1000)}</p>")))
        val reading = api.fetchReading("Data_structure")
        assertTrue(reading.excerpt.length in 1..8000)
    }

    @Test fun `missing revision blocks a reusable snapshot`() = runBlocking<Unit> {
        server.enqueue(json(page("<p>Useful article</p>").replace("\"latest\":{\"id\":1377666699,\"timestamp\":\"2026-09-30T12:52:14Z\"},", "")))
        expect<MediaWikiException.InvalidResponse> { api.fetchReading("Data_structure") }
    }

    @Test fun `invalid JSON fails instead of fabricating live readings`() = runBlocking<Unit> {
        server.enqueue(json("not JSON"))
        expect<MediaWikiException.InvalidResponse> { api.search("database") }
    }

    @Test fun `a missing article becomes a typed not-found error`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(404))
        expect<MediaWikiException.NotFound> { api.fetchReading("Missing") }
        assertEquals(1, server.requestCount)
    }

    @Test fun `429 honors Retry-After then retries once`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))
        server.enqueue(json("{\"pages\":[]}"))
        assertTrue(api.search("database").isEmpty())
        assertEquals(listOf(2000L), waits)
        assertEquals(2, server.requestCount)
    }

    @Test fun `long Retry-After is reported without retrying too early`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        val error = expect<MediaWikiException.RateLimited> { api.search("database") }
        assertEquals(120_000L, error.retryAfterMillis)
        assertTrue(waits.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun `repeated throttling has bounded attempts`() = runBlocking<Unit> {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1")) }
        expect<MediaWikiException.RateLimited> { api.search("database") }
        assertEquals(2, server.requestCount)
    }

    @Test fun `503 honors a short Retry-After then retries once`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "3"))
        server.enqueue(json("{\"pages\":[]}"))
        assertTrue(api.search("database").isEmpty())
        assertEquals(listOf(3000L), waits)
        assertEquals(2, server.requestCount)
    }

    @Test fun `503 with a long Retry-After does not retry early`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "60"))
        val error = expect<MediaWikiException.RateLimited> { api.search("database") }
        assertEquals(60_000L, error.retryAfterMillis)
        assertTrue(waits.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun `503 without Retry-After is reported without an automatic retry`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(503))
        val error = expect<MediaWikiException.HttpFailure> { api.search("database") }
        assertEquals(503, error.status)
        assertTrue(waits.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun `repeated 503 responses stop after one bounded retry`() = runBlocking<Unit> {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "1")) }
        expect<MediaWikiException.RateLimited> { api.search("database") }
        assertEquals(listOf(1000L), waits)
        assertEquals(2, server.requestCount)
    }

    @Test(timeout = 5000) fun `connection loss reports an IO failure without application retries`() = runBlocking<Unit> {
        val disconnectedApi = apiWithClient(OkHttpClient.Builder()
            .retryOnConnectionFailure(false).callTimeout(2, TimeUnit.SECONDS).build())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        expect<IOException> { disconnectedApi.search("database") }
        assertTrue(waits.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test(timeout = 5000) fun `unresponsive service respects the configured read timeout without retrying`() = runBlocking<Unit> {
        val timeoutApi = apiWithClient(OkHttpClient.Builder()
            .retryOnConnectionFailure(false).readTimeout(200, TimeUnit.MILLISECONDS)
            .callTimeout(2, TimeUnit.SECONDS).build())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        expect<SocketTimeoutException> { timeoutApi.fetchReading("Data_structure") }
        assertTrue(waits.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test(timeout = 5000) fun `cancelling a search cancels its network call and releases the request slot`() = runBlocking<Unit> {
        val failed = CountDownLatch(1)
        val networkCancelled = AtomicBoolean(false)
        val cancellationApi = apiWithClient(OkHttpClient.Builder()
            .retryOnConnectionFailure(false).callTimeout(2, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) {
                    networkCancelled.set(call.isCanceled())
                    failed.countDown()
                }
            }).build())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(json("{\"pages\":[]}"))
        val pending = async(start = CoroutineStart.UNDISPATCHED) { cancellationApi.search("first") }
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertTrue("The cancelled HTTP call must finish", failed.await(1, TimeUnit.SECONDS))
        assertTrue("Cancellation must reach OkHttp", networkCancelled.get())
        assertTrue(cancellationApi.search("second").isEmpty())
        assertEquals(2, server.requestCount)
        assertTrue(waits.isEmpty())
    }

    @Test(timeout = 8000) fun `cancelling after response headers also cancels the blocked body read`() = runBlocking<Unit> {
        val bodyStarted = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val networkCancelled = AtomicBoolean(false)
        val cancellationApi = apiWithClient(OkHttpClient.Builder()
            .retryOnConnectionFailure(false).callTimeout(5, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) { bodyStarted.countDown() }
                override fun callFailed(call: Call, ioe: IOException) {
                    networkCancelled.set(call.isCanceled())
                    failed.countDown()
                }
            }).build())
        // OkHttp emits responseBodyStart only after its first read. Supply a chunk, then block later reads.
        server.enqueue(json(page("<p>A useful data structure.</p>")).throttleBody(20, 2, TimeUnit.SECONDS))
        val pending = async(start = CoroutineStart.UNDISPATCHED) { cancellationApi.fetchReading("Data_structure") }
        assertTrue("The response body must start before cancellation", kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            bodyStarted.await(1, TimeUnit.SECONDS)
        })
        pending.cancel()
        val interruptedPromptly = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            failed.await(1, TimeUnit.SECONDS)
        }
        pending.join()
        assertTrue("Cancellation must interrupt the body read before the call timeout", interruptedPromptly)
        assertTrue(networkCancelled.get())
        assertEquals(1, server.requestCount)
    }

    @Test fun `an HTML error page is rejected instead of becoming a reading`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html")
            .setBody("<html><body><p>Service unavailable</p><script>steal()</script></body></html>"))
        expect<MediaWikiException.InvalidResponse> { api.fetchReading("Data_structure") }
        assertEquals(1, server.requestCount)
    }

    @Test fun `hostile excerpt links become plain text without executable URLs`() = runBlocking<Unit> {
        server.enqueue(json(page("<p>Read <a href='javascript:steal()' onclick='steal()'>this topic</a> and <a href='https://evil.example/track'>related text</a>.</p>")))
        val reading = api.fetchReading("Data_structure")
        assertEquals("Read this topic and related text.", reading.excerpt)
        assertFalse(reading.excerpt.contains("javascript:"))
        assertFalse(reading.excerpt.contains("evil.example"))
        assertFalse(reading.excerpt.contains("steal"))
        assertEquals("https://en.wikipedia.org/wiki/Data_structure", reading.sourceUrl)
        assertEquals(1, server.requestCount)
    }

    @Test fun `blank query does not perform a request`() = runBlocking<Unit> {
        expect<IllegalArgumentException> { api.search("  ") }
        assertEquals(0, server.requestCount)
    }

    @Test fun `unsafe license metadata cannot be saved as reusable content`() = runBlocking<Unit> {
        server.enqueue(json(page("<p>Useful article</p>").replace("https://creativecommons.org/", "http://evil.example/")))
        expect<MediaWikiException.InvalidResponse> { api.fetchReading("Data_structure") }
    }

    @Test fun `license URLs must match the canonical BY-SA forms accepted by reading rules`() = runBlocking<Unit> {
        val unsupported = listOf(
            "https://creativecommons.org/licenses/by/4.0/",
            "https://creativecommons.org/licenses/zero/1.0/",
            "https://creativecommons.org/licenses/by-sa/4.0/deed.en?redirect=elsewhere",
            "https://creativecommons.org/licenses/by-sa/4.0/deed.en#extra",
            "https://user:secret@creativecommons.org/licenses/by-sa/4.0/",
            "https://:secret@creativecommons.org/licenses/by-sa/4.0/",
            "https://creativecommons.org:444/licenses/by-sa/4.0/",
            "https://creativecommons.org:443/licenses/by-sa/4.0/",
            "https://creativecommons.org/licenses/by-sa/4.0/../deed.en",
            "https://creativecommons.org/licenses/by-sa/4.0/%64eed.en",
            "https://creativecommons.org/licenses/by-sa/4.0/deed.en/extra",
            "https://creativecommons.org.evil.example/licenses/by-sa/4.0/",
        )
        for (url in unsupported) {
            server.enqueue(json(page("<p>Useful article</p>")
                .replace("https://creativecommons.org/licenses/by-sa/4.0/deed.en", url)))
            expect<MediaWikiException.InvalidResponse> { api.fetchReading("Data_structure") }
        }
        assertEquals(unsupported.size, server.requestCount)
    }

    @Test fun `canonical BY-SA license metadata is preserved without rewriting`() = runBlocking<Unit> {
        for (url in listOf("https://creativecommons.org/licenses/by-sa/4.0/",
            "https://creativecommons.org/licenses/by-sa/3.0/deed.en")) {
            server.enqueue(json(page("<p>Useful article</p>")
                .replace("https://creativecommons.org/licenses/by-sa/4.0/deed.en", url)))
            assertEquals(url, api.fetchReading("Data_structure").licenseUrl)
        }
    }

    @Test fun `HTTP date Retry-After is honored`() = runBlocking<Unit> {
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(1_700_000_003_000).atZone(ZoneOffset.UTC))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", date))
        server.enqueue(json("{\"pages\":[]}"))
        api.search("database")
        assertEquals(listOf(3000L), waits)
    }

    @Test fun `article key remains one encoded path segment`() = runBlocking<Unit> {
        server.enqueue(json(page("<p>A useful article paragraph.</p>")))
        api.fetchReading("C++ / data")
        assertTrue(server.takeRequest().requestUrl!!.encodedPath.contains("%2F"))
    }

    @Test fun `oversized responses fail before content can be saved`() = runBlocking<Unit> {
        server.enqueue(json("x".repeat(2_000_001)))
        expect<MediaWikiException.InvalidResponse> { api.fetchReading("Data_structure") }
    }

    @Test fun `only one submitted request is in flight`() = runBlocking<Unit> {
        server.enqueue(json("{\"pages\":[]}").setHeadersDelay(500, TimeUnit.MILLISECONDS))
        server.enqueue(json("{\"pages\":[]}"))
        val first = async(start = CoroutineStart.UNDISPATCHED) { api.search("first") }
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        val second = async(start = CoroutineStart.UNDISPATCHED) { api.search("second") }
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        first.await()
        second.await()
        assertEquals(2, server.requestCount)
    }

    @Test fun `attribution notices cannot be silently dropped to fit the database`() = runBlocking<Unit> {
        server.enqueue(json(page("<p>Useful paragraph.</p><p class='attribution'>${"Credit ".repeat(100)}</p>")))
        expect<MediaWikiException.InvalidResponse> { api.fetchReading("Data_structure") }
    }

    private fun apiWithClient(client: OkHttpClient) = MediaWikiClient(
        client = client,
        userAgent = "StudyPath-test/1.0 (test fixture)",
        // The server listens on IPv4; a cancelled localhost route must not send the next call to ::1.
        baseUrl = server.url("/w/rest.php/v1/").newBuilder().host("127.0.0.1").build(),
        now = { 1_700_000_000_000 },
        wait = { waits += it },
    )

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun page(html: String): String = """{"id":8519,"key":"Data_structure","title":"Data structure","latest":{"id":1377666699,"timestamp":"2026-09-30T12:52:14Z"},"license":{"title":"Creative Commons Attribution-Share Alike 4.0","url":"https://creativecommons.org/licenses/by-sa/4.0/deed.en"},"html":"$html"}"""

    private suspend inline fun <reified T : Throwable> expect(noinline action: suspend () -> Unit): T {
        try { action() } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        fail("Expected ${T::class.java.simpleName}")
        error("unreachable")
    }
}
