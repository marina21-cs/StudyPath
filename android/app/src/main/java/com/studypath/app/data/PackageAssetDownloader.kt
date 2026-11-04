package com.studypath.app.data

import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

internal fun interface PackageAssetTransport { fun open(asset: PackageAssetDescriptor): PackageAssetResponse }
internal class PackageAssetResponse(val status: Int, val mime: String?, val length: Long, val stream: InputStream, private val closeResponse: () -> Unit) : Closeable {
    override fun close() { try { stream.close() } finally { closeResponse() } }
}

class DownloadedPackageAssets internal constructor(val files: Map<String, File>, private val directory: File, private val released: () -> Unit) : Closeable {
    override fun close() { try { check(!directory.exists() || directory.deleteRecursively()) { "Could not discard temporary asset bytes." } } finally { released() } }
}

/** Fetches only the public origins from an already verified immutable package; never follows redirects. */
class PackageAssetDownloader internal constructor(private val transport: PackageAssetTransport) {
    constructor(client: OkHttpClient, userAgent: String) : this(network(client, userAgent))

    fun download(verified: VerifiedTextPackage, directory: File, checkActive: () -> Unit = {}): DownloadedPackageAssets {
        val checked = TextPackageVerifier.verify(verified.summary, verified.manifestJson, verified.textJson)
        require(directory.isDirectory || directory.mkdirs()) { "Could not create app-private download storage." }
        require(!Files.isSymbolicLink(directory.toPath()) && directory.canonicalFile == directory.absoluteFile)
        val staging = synchronized(activeStaging) {
            directory.listFiles().orEmpty().filter { interruptedName.matches(it.name) && it.canonicalFile !in activeStaging }.forEach { abandoned ->
                if (Files.isSymbolicLink(abandoned.toPath())) check(abandoned.delete()) { "Could not discard interrupted asset staging." }
                else check(abandoned.deleteRecursively()) { "Could not discard interrupted asset staging." }
            }
            require(directory.usableSpace >= checked.summary.assetBytes + 16_384) { "Not enough storage to download the complete package." }
            File(directory, ".package-${UUID.randomUUID()}.tmp").also {
                check(it.mkdir()) { "Could not create app-private asset staging." }; activeStaging.add(it.canonicalFile)
            }
        }
        val files = linkedMapOf<String, File>()
        try {
            checked.assets.forEach { asset ->
                checkActive()
                val output = File(staging, asset.file)
                transport.open(asset).use { response ->
                    require(response.status == 200 && response.mime == "application/pdf" &&
                        (response.length == -1L || response.length == asset.bytes)) { "PDF response status, type or length differs from its manifest." }
                    FileOutputStream(output).use { stream ->
                        CompletePackageAssets.copyAndVerify(response.stream, stream, asset, checkActive)
                        stream.fd.sync()
                    }
                }
                files[asset.id] = output
            }
            checkActive()
            return DownloadedPackageAssets(files.toMap(), staging) { synchronized(activeStaging) { activeStaging.remove(staging.canonicalFile) } }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            synchronized(activeStaging) { activeStaging.remove(staging.canonicalFile) }
            throw error
        }
    }

    companion object {
        private val activeStaging = mutableSetOf<File>()
        private val interruptedName = Regex("\\.package-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.tmp")
        internal fun publicAddress(address: InetAddress): Boolean {
            val bytes = address.address
            val uniqueLocal = bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
            val a = bytes[0].toInt() and 255; val b = bytes[1].toInt() and 255
            val c = if (bytes.size == 4) bytes[2].toInt() and 255 else -1
            val reservedV4 = bytes.size == 4 && (a == 0 || a >= 224 || (a == 100 && b in 64..127) ||
                (a == 192 && b == 0) || (a == 198 && b in 18..19) || (a == 198 && b == 51 && c == 100) ||
                (a == 203 && b == 0 && c == 113))
            val nonGlobalV6 = bytes.size == 16 && (a and 0xe0) != 0x20
            return !uniqueLocal && !reservedV4 && !nonGlobalV6 && !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isSiteLocalAddress &&
                !address.isLinkLocalAddress && !address.isMulticastAddress
        }

        private fun network(original: OkHttpClient, userAgent: String): PackageAssetTransport {
            require(userAgent.isNotBlank() && !userAgent.contains('\n') && !userAgent.contains('\r'))
            val client = original.newBuilder().followRedirects(false).followSslRedirects(false)
                .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
                .proxy(Proxy.NO_PROXY).cache(null)
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = original.dns.lookup(hostname).also { answers ->
                        if (answers.isEmpty() || answers.any { !publicAddress(it) }) throw UnknownHostException("Asset origin resolved to a non-public address.")
                    }
                }).build()
            return PackageAssetTransport { asset ->
                val response = client.newCall(Request.Builder().url(asset.url).header("Accept", "application/pdf")
                    .header("User-Agent", userAgent).build()).execute()
                val body = response.body
                if (body == null) { response.close(); throw IllegalArgumentException("Missing PDF response body.") }
                val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }
                PackageAssetResponse(response.code, mime, body.contentLength(), body.byteStream(), response::close)
            }
        }
    }
}
