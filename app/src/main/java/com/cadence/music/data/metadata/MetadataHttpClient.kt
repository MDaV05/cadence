package com.cadence.music.data.metadata

import com.cadence.music.data.source.sameOrigin
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class HttpResponse(
    val code: Int,
    val body: String?,
) {
    val isSuccessful: Boolean get() = code in 200..299
}

object MetadataHttpClient {
    const val USER_AGENT = "Cadence/1.0 ( https://github.com/MDaV05/cadence )"
    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 15_000
    const val MAX_BODY_BYTES = 2 * 1024 * 1024 // 2MB
    const val MAX_REDIRECTS = 3

    /**
     * RFC 3986 percent-encoding for path and query components (spaces as %20).
     */
    fun encode(value: String): String =
        runCatching {
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        }.getOrDefault(value)

    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        execute(method = "GET", url = url, headers = headers, body = null)

    fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        execute(method = "POST", url = url, headers = headers, body = body)

    fun execute(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): HttpResponse = try {
        fun openConnection(targetUrl: URL): HttpURLConnection =
            (targetUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("User-Agent", USER_AGENT)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                if (body != null) {
                    doOutput = true
                }
            }

        var conn = openConnection(URL(url))
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            var code = conn.responseCode
            var hops = 0
            while (code in 300..399 && hops < MAX_REDIRECTS) {
                val loc = conn.getHeaderField("Location") ?: break
                val next = runCatching { URL(conn.url, loc) }.getOrNull() ?: break
                if (!sameOrigin(conn.url.toString(), next.toString())) break
                conn.disconnect()
                conn = openConnection(next)
                code = conn.responseCode
                hops++
            }
            val responseBody = if (code in 200..299) {
                conn.inputStream.use { it.readNBytes(MAX_BODY_BYTES) }.decodeToString()
            } else {
                null
            }
            HttpResponse(code = code, body = responseBody)
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        HttpResponse(code = -1, body = null)
    }
}
