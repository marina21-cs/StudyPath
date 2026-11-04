package com.studypath.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Real OkHttp disk-cache fixtures; no Wikimedia or Firebase service is contacted. */
class MediaWikiCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() = server.shutdown()

    @Test fun `fresh cached reading retains its original retrieval time and immutable provenance`() = runBlocking<Unit> {
        Cache(temporary.newFolder(), 1_000_000).use { cache ->
            var clock = 1_700_000_000_000L
            val responseTimes = mutableListOf<Long>()
            val client = client(cache).newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request()).also { responseTimes += it.receivedResponseAtMillis }
            }.build()
            val api = api(client) { clock }
            server.enqueue(json(page()).setHeader("Cache-Control", "public, max-age=3600"))
            val first = api.fetchReading("Data_structure")
            clock += 60_000
            val cached = api.fetchReading("Data_structure")
            assertEquals(1, server.requestCount)
            assertEquals(1, cache.hitCount())
            assertEquals(first, cached)
            assertEquals("Retrieved time must describe the HTTP receipt, including a cached receipt", responseTimes.first(), first.retrievedAt)
            assertEquals(responseTimes.first(), responseTimes.last())
        }
    }

    @Test fun `stale reading revalidates its ETag and keeps the exact revision text on 304`() = runBlocking<Unit> {
        Cache(temporary.newFolder(), 1_000_000).use { cache ->
            val responseTimes = mutableListOf<Long>()
            val networkCodes = mutableListOf<Int?>()
            val api = api(client(cache).newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request()).also {
                    responseTimes += it.receivedResponseAtMillis
                    networkCodes += it.networkResponse?.code
                }
            }.build())
            server.enqueue(json(page()).setHeader("Cache-Control", "max-age=0, must-revalidate").setHeader("ETag", "\"revision-1377666699\""))
            server.enqueue(MockResponse().setResponseCode(304).setHeader("Cache-Control", "max-age=3600")
                .setHeader("ETag", "\"revision-1377666699\""))
            val first = api.fetchReading("Data_structure")
            val checked = api.fetchReading("Data_structure")
            assertNull(server.takeRequest().getHeader("If-None-Match"))
            assertEquals("\"revision-1377666699\"", server.takeRequest().getHeader("If-None-Match"))
            assertEquals(listOf(200, 304), networkCodes)
            assertEquals(first, checked.copy(retrievedAt = first.retrievedAt))
            assertEquals(responseTimes.last(), checked.retrievedAt)
            assertEquals(2, server.requestCount)
            assertEquals(1, cache.hitCount())
        }
    }

    @Test fun `stale search uses Last-Modified when the service supplies no ETag`() = runBlocking<Unit> {
        Cache(temporary.newFolder(), 1_000_000).use { cache ->
            val api = api(client(cache))
            val modified = "Wed, 30 Sep 2026 12:52:14 GMT"
            server.enqueue(json(search("first")).setHeader("Cache-Control", "max-age=0, must-revalidate")
                .setHeader("Last-Modified", modified))
            server.enqueue(MockResponse().setResponseCode(304).setHeader("Cache-Control", "max-age=3600"))
            assertEquals(api.search("data"), api.search("data"))
            assertNull(server.takeRequest().getHeader("If-Modified-Since"))
            val conditional = server.takeRequest()
            assertEquals(modified, conditional.getHeader("If-Modified-Since"))
            assertNull(conditional.getHeader("If-None-Match"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `no-store responses are fetched again and do not retain validators`() = runBlocking<Unit> {
        Cache(temporary.newFolder(), 1_000_000).use { cache ->
            val api = api(client(cache))
            server.enqueue(json(search("first")).setHeader("Cache-Control", "no-store, max-age=3600").setHeader("ETag", "\"one\""))
            server.enqueue(json(search("second")).setHeader("Cache-Control", "no-store, max-age=3600"))
            assertEquals("first", api.search("data").single().title)
            assertEquals("second", api.search("data").single().title)
            server.takeRequest()
            assertNull(server.takeRequest().getHeader("If-None-Match"))
            assertEquals(2, server.requestCount)
            assertEquals(0, cache.hitCount())
            assertFalse(cache.urls().hasNext())
        }
    }

    @Test fun `no-cache requires validation even when max-age would otherwise be fresh`() = runBlocking<Unit> {
        Cache(temporary.newFolder(), 1_000_000).use { cache ->
            val api = api(client(cache))
            server.enqueue(json(search("first")).setHeader("Cache-Control", "no-cache, max-age=3600").setHeader("ETag", "\"one\""))
            server.enqueue(MockResponse().setResponseCode(304))
            assertEquals(api.search("data"), api.search("data"))
            server.takeRequest()
            assertEquals("\"one\"", server.takeRequest().getHeader("If-None-Match"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `failed required revalidation never substitutes stale success`() = runBlocking<Unit> {
        Cache(temporary.newFolder(), 1_000_000).use { cache ->
            val api = api(client(cache))
            server.enqueue(json(page()).setHeader("Cache-Control", "max-age=0, must-revalidate").setHeader("ETag", "\"one\""))
            server.enqueue(MockResponse().setResponseCode(503))
            api.fetchReading("Data_structure")
            val error = try {
                api.fetchReading("Data_structure")
                fail("Required revalidation must report the service failure")
                error("unreachable")
            } catch (error: MediaWikiException.HttpFailure) { error }
            assertEquals(503, error.status)
            assertEquals(2, server.requestCount)
            assertEquals(0, cache.hitCount())
        }
    }

    @Test fun `different submitted queries have separate cached result sets`() = runBlocking<Unit> {
        Cache(temporary.newFolder(), 1_000_000).use { cache ->
            val api = api(client(cache))
            server.enqueue(json(search("data")).setHeader("Cache-Control", "max-age=3600"))
            server.enqueue(json(search("programming")).setHeader("Cache-Control", "max-age=3600"))
            assertEquals("data", api.search("data").single().title)
            assertEquals("programming", api.search("programming").single().title)
            assertEquals("data", api.search("data").single().title)
            assertEquals(2, server.requestCount)
            assertEquals("data", server.takeRequest().requestUrl!!.queryParameter("q"))
            assertEquals("programming", server.takeRequest().requestUrl!!.queryParameter("q"))
        }
    }

    @Test fun `disk cache reopened by a new client preserves the original reading receipt`() = runBlocking<Unit> {
        val directory = temporary.newFolder()
        val first = Cache(directory, 1_000_000).use { cache ->
            server.enqueue(json(page()).setHeader("Cache-Control", "max-age=3600"))
            api(client(cache)) { 1_700_000_000_000 }.fetchReading("Data_structure")
        }
        Cache(directory, 1_000_000).use { cache ->
            val restored = api(client(cache)) { 1_700_001_000_000 }.fetchReading("Data_structure")
            assertEquals(first, restored)
            assertEquals(1, server.requestCount)
            assertEquals(1, cache.hitCount())
        }
    }

    private fun client(cache: Cache) = OkHttpClient.Builder().cache(cache)
        .retryOnConnectionFailure(false).callTimeout(2, TimeUnit.SECONDS).build()

    private fun api(client: OkHttpClient, clock: () -> Long = System::currentTimeMillis) = MediaWikiClient(
        client, "StudyPath-cache-test/1.0 (local fixture)",
        server.url("/w/rest.php/v1/").newBuilder().host("127.0.0.1").build(), clock,
    )

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setHeader("Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().atZone(ZoneOffset.UTC))).setBody(body)
    private fun search(title: String) = """{"pages":[{"id":8519,"key":"Data_structure","title":"$title"}]}"""
    private fun page() = """{"id":8519,"key":"Data_structure","title":"Data structure","latest":{"id":1377666699,"timestamp":"2026-09-30T12:52:14Z"},"license":{"title":"Creative Commons Attribution-Share Alike 4.0","url":"https://creativecommons.org/licenses/by-sa/4.0/deed.en"},"html":"<p>A useful data structure.</p>"}"""
}
