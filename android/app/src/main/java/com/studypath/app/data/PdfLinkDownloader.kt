package com.studypath.app.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PdfLinkDownloader(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).callTimeout(90, TimeUnit.SECONDS).build()) {
    companion object {
        fun validate(value: String): HttpUrl {
            val url = requireNotNull(value.trim().toHttpUrlOrNull()) { "Paste a complete HTTPS PDF link." }
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()) { "Use an HTTPS link without embedded credentials." }
            require(url.toString().length <= 4096) { "This link is too long." }
            return url
        }
    }
    suspend fun download(address: String, target: File, progress: (Long, Long) -> Unit): Unit = suspendCancellableCoroutine { continuation ->
        var active: Call? = null
        continuation.invokeOnCancellation { active?.cancel() }
        fun fetch(url: HttpUrl, redirects: Int) {
            if (!continuation.isActive) return
            val call = client.newCall(Request.Builder().url(url).header("Accept", "application/pdf").build())
            active = call
            if (!continuation.isActive) { call.cancel(); return }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { target.delete(); if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { r ->
                            if (r.code in 300..399) {
                                require(redirects < 5) { "Too many redirects. Use a direct PDF link." }
                                val next = requireNotNull(r.header("Location")?.let(url::resolve)) { "The source returned an invalid redirect." }
                                validate(next.toString()); fetch(next, redirects + 1); return
                            }
                            require(r.isSuccessful) { "The source returned HTTP ${r.code}. Check the link or try again." }
                            val body = requireNotNull(r.body) { "The source returned an empty file." }
                            val length = body.contentLength()
                            require(length <= PersonalStudyStore.MAX_PDF_BYTES) { "PDFs must be 25 MB or smaller." }
                            body.byteStream().use { input -> FileOutputStream(target).use { output ->
                                val buffer = ByteArray(8192); var total = 0L
                                while (true) {
                                    if (!continuation.isActive) throw IOException("Download cancelled")
                                    val count=input.read(buffer); if(count<0) break
                                    total+=count; require(total<=PersonalStudyStore.MAX_PDF_BYTES) { "PDFs must be 25 MB or smaller." }
                                    output.write(buffer,0,count); progress(total,length)
                                }
                                require(total>0) { "The source returned an empty file." }
                                output.fd.sync()
                            } }
                        }
                        if (continuation.isActive) continuation.resume(Unit) else target.delete()
                    } catch (e: Exception) { target.delete(); if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
        try { fetch(validate(address),0) } catch(e: Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
    }
}
