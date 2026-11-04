package com.studypath.app.data

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.studypath.app.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class FamilyTimeSource { HTTP_DATE, SYNTHETIC_LOCAL_CLOCK }
internal data class FamilyRestResponse(val value: JsonElement, val etag: String?, val serverNow: Long,
                                      val timeSource: FamilyTimeSource = FamilyTimeSource.HTTP_DATE)
internal fun familyResponseTime(date: String?, localDemo: Boolean): Pair<Long, FamilyTimeSource> {
    // Firebase's local database emulator omits Date. This clock is synthetic test provenance,
    // never a cloud fallback or proof of authorization; current rules still use server `now`.
    if (date == null && localDemo && BuildConfig.DEBUG && BuildConfig.USE_FIREBASE_EMULATORS) {
        return System.currentTimeMillis() to FamilyTimeSource.SYNTHETIC_LOCAL_CLOCK
    }
    val now = ZonedDateTime.parse(requireNotNull(date), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    return now to FamilyTimeSource.HTTP_DATE
}
internal class FamilyRestFailure(val status: Int) : IOException(when (status) {
    401, 403 -> "Sharing authorization was denied. Reconnect or review the invitation and permission."
    412 -> "Sharing changed on the server. Refresh before trying again."
    else -> "The sharing request was not confirmed by the server."
})

/** Token-authenticated, bounded REST with no SDK, HTTP, cookie or disk summary cache. */
class FamilySharingNetwork(client: OkHttpClient, private val base: HttpUrl, val localDemo: Boolean) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cache(null).cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).proxy(Proxy.NO_PROXY)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .apply { interceptors().clear(); networkInterceptors().clear(); eventListener(EventListener.NONE) }.build()
    init {
        val local = base.scheme == "http" && base.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
        val cloud = base.scheme == "https" && base.port == 443 &&
            (base.host.endsWith(".firebaseio.com") || base.host.endsWith(".firebasedatabase.app"))
        require((localDemo && local || !localDemo && cloud) && base.encodedPath == "/" &&
            base.username.isEmpty() && base.password.isEmpty() && base.fragment == null)
        require(base.queryParameterNames.all { localDemo && it == "ns" } && base.querySize <= 1)
        if (localDemo) require(base.queryParameter("ns")?.matches(Regex("demo-studypath(?:-[A-Za-z0-9_-]+)?")) == true)
    }
    internal suspend fun request(path: List<String>, token: String, method: String = "GET", value: JsonElement? = null,
                                 etag: String? = null, readEtag: Boolean = false): FamilyRestResponse = suspendCancellableCoroutine { continuation ->
        require(path.isNotEmpty() && path.all { it.matches(Regex("[A-Za-z0-9_:-]{1,200}")) })
        require(token.matches(Regex("[A-Za-z0-9_.-]{1,8192}")))
        require(method in setOf("GET", "PUT", "PATCH"))
        val url = base.newBuilder().apply { path.dropLast(1).forEach(::addPathSegment); addPathSegment(path.last() + ".json"); addQueryParameter("auth", token) }.build()
        val builder = Request.Builder().url(url).header("Cache-Control", "no-cache, no-store").header("Accept", "application/json")
        if (readEtag) builder.header("X-Firebase-ETag", "true")
        if (etag != null) builder.header("if-match", etag)
        if (method == "GET") builder.get() else builder.method(method, requireNotNull(value).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        val call = client.newCall(builder.build()); continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("A fresh sharing connection is unavailable. No cached summary was used."))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (response.code != 200) throw FamilyRestFailure(response.code)
                        val (now, timeSource) = familyResponseTime(response.header("Date"), localDemo)
                        val body = requireNotNull(response.body); require(body.contentLength() <= 2_097_152)
                        val bytes = ByteArrayOutputStream(); val chunk = ByteArray(4096)
                        body.byteStream().use { input ->
                            while (continuation.isActive) {
                                val count = input.read(chunk); if (count < 0) break
                                require(bytes.size() + count <= 2_097_152); bytes.write(chunk, 0, count)
                            }
                        }
                        if (continuation.isActive) continuation.resume(FamilyRestResponse(JsonParser.parseString(bytes.toString("UTF-8")), response.header("ETag"), now, timeSource))
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(if (error is FamilyRestFailure) error else IOException("The fresh sharing response was invalid or too large."))
                    }
                }
            }
        })
    }
}
