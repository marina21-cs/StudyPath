package com.studypath.app.data

import com.studypath.app.BuildConfig
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Explicit development mirror. Production downloads retain their public HTTPS/DNS controls. */
internal object MockPackageAssets {
    fun downloader(publicOrigin: HttpUrl, mirrorOrigin: HttpUrl): PackageAssetDownloader {
        require(BuildConfig.DEBUG && BuildConfig.USE_FIREBASE_EMULATORS) { "Mock assets require a local debug build." }
        require(publicOrigin.scheme == "https" && publicOrigin.host == "mock-assets.example.org" && publicOrigin.port == 443 &&
            publicOrigin.encodedPath == "/" && publicOrigin.query == null && publicOrigin.fragment == null &&
            publicOrigin.username.isEmpty() && publicOrigin.password.isEmpty())
        require(mirrorOrigin.scheme == "http" && mirrorOrigin.host in setOf("127.0.0.1", "localhost", "10.0.2.2") &&
            mirrorOrigin.port in 1024..65535 && mirrorOrigin.encodedPath == "/" && mirrorOrigin.query == null &&
            mirrorOrigin.fragment == null && mirrorOrigin.username.isEmpty() && mirrorOrigin.password.isEmpty())
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .cache(null).cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .proxy(Proxy.NO_PROXY).eventListener(EventListener.NONE).connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
        return PackageAssetDownloader(PackageAssetTransport { asset ->
            val url = HttpUrl.Builder().scheme("https").host("mock-assets.example.org").build().resolve(asset.url)
                ?: error("Invalid mock asset address.")
            require(url.scheme == publicOrigin.scheme && url.host == publicOrigin.host && url.port == publicOrigin.port &&
                url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Only the declared mock asset origin may use this mirror." }
            val target = mirrorOrigin.newBuilder().encodedPath(url.encodedPath).build()
            val response = client.newCall(Request.Builder().url(target).header("Accept", "application/pdf")
                .header("User-Agent", BuildConfig.API_USER_AGENT).build()).execute()
            val body = response.body
            if (body == null) { response.close(); error("The mock asset mirror returned no file.") }
            PackageAssetResponse(response.code, body.contentType()?.let { "${it.type}/${it.subtype}" },
                body.contentLength(), body.byteStream(), response::close)
        })
    }
}
