package com.cadence.music.data.metadata

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

class MetadataHttpClientTest {

    data class MockRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    data class MockResponse(
        val code: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
    )

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var baseUrl: String = ""
    @Volatile
    private var handler: (MockRequest) -> MockResponse = { MockResponse(200, body = "OK") }

    @Before
    fun setUp() {
        val ss = ServerSocket(0)
        serverSocket = ss
        baseUrl = "http://127.0.0.1:${ss.localPort}"
        serverThread = Thread {
            try {
                while (!ss.isClosed) {
                    val socket = ss.accept()
                    Thread {
                        handleConnection(socket)
                    }.start()
                }
            } catch (_: SocketException) {
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val path = parts.getOrNull(1) ?: "/"

                val headers = mutableMapOf<String, String>()
                var line: String? = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    val colonIdx = line.indexOf(':')
                    if (colonIdx > 0) {
                        val k = line.substring(0, colonIdx).trim()
                        val v = line.substring(colonIdx + 1).trim()
                        headers[k.lowercase()] = v
                    }
                    line = reader.readLine()
                }

                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val body = if (contentLength > 0) {
                    val charArray = CharArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val r = reader.read(charArray, readTotal, contentLength - readTotal)
                        if (r == -1) break
                        readTotal += r
                    }
                    String(charArray, 0, readTotal)
                } else {
                    ""
                }

                val response = handler(MockRequest(method, path, headers, body))
                val out = s.getOutputStream()
                val statusText = when (response.code) {
                    200 -> "OK"
                    301 -> "Moved Permanently"
                    302 -> "Found"
                    404 -> "Not Found"
                    else -> "Status"
                }
                val responseBytes = response.body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                val headerBuilder = StringBuilder()
                headerBuilder.append("HTTP/1.1 ${response.code} $statusText\r\n")
                headerBuilder.append("Content-Length: ${responseBytes.size}\r\n")
                headerBuilder.append("Connection: close\r\n")
                for ((k, v) in response.headers) {
                    headerBuilder.append("$k: $v\r\n")
                }
                headerBuilder.append("\r\n")
                out.write(headerBuilder.toString().toByteArray(Charsets.UTF_8))
                if (responseBytes.isNotEmpty()) {
                    out.write(responseBytes)
                }
                out.flush()
            }
        } catch (_: Exception) {
        }
    }

    @After
    fun tearDown() {
        serverSocket?.close()
        serverThread?.interrupt()
    }

    @Test
    fun `encode formats spaces as percent twenty according to RFC 3986`() {
        assertEquals("Hello%20World", MetadataHttpClient.encode("Hello World"))
        assertEquals("Rock%20%26%20Roll", MetadataHttpClient.encode("Rock & Roll"))
        assertEquals("SimpleText123", MetadataHttpClient.encode("SimpleText123"))
        assertEquals("A%2BB", MetadataHttpClient.encode("A+B"))
    }

    @Test
    fun `get sends user-agent and receives 200 response`() {
        var recordedUserAgent: String? = null
        var recordedHeader: String? = null

        handler = { req ->
            recordedUserAgent = req.headers["user-agent"]
            recordedHeader = req.headers["x-custom-header"]
            MockResponse(200, body = "OK")
        }

        val res = MetadataHttpClient.get(
            url = "$baseUrl/test",
            headers = mapOf("X-Custom-Header" to "CustomValue"),
        )

        assertTrue(res.isSuccessful)
        assertEquals(200, res.code)
        assertEquals("OK", res.body)
        assertEquals(MetadataHttpClient.USER_AGENT, recordedUserAgent)
        assertEquals("CustomValue", recordedHeader)
    }

    @Test
    fun `post sends request body and headers`() {
        var recordedBody: String? = null
        var recordedAuth: String? = null

        handler = { req ->
            recordedAuth = req.headers["authorization"]
            recordedBody = req.body
            MockResponse(200, body = """{"status":"ok"}""")
        }

        val res = MetadataHttpClient.post(
            url = "$baseUrl/submit",
            body = """{"message":"hello"}""",
            headers = mapOf("Authorization" to "Token secret"),
        )

        assertEquals(200, res.code)
        assertEquals("""{"status":"ok"}""", res.body)
        assertEquals("Token secret", recordedAuth)
        assertEquals("""{"message":"hello"}""", recordedBody)
    }

    @Test
    fun `bounded response caps body at 2MB`() {
        val largeBody = "A".repeat(3 * 1024 * 1024)
        handler = {
            MockResponse(200, body = largeBody)
        }

        val res = MetadataHttpClient.get("$baseUrl/large")
        assertEquals(200, res.code)
        assertNotNull(res.body)
        // Body must be capped at MAX_BODY_BYTES (2 * 1024 * 1024)
        assertEquals(MetadataHttpClient.MAX_BODY_BYTES, res.body!!.toByteArray().size)
    }

    @Test
    fun `follows same-origin redirects up to 3 hops`() {
        handler = { req ->
            when (req.path) {
                "/hop1" -> MockResponse(302, headers = mapOf("Location" to "$baseUrl/hop2"))
                "/hop2" -> MockResponse(301, headers = mapOf("Location" to "$baseUrl/final"))
                "/final" -> MockResponse(200, body = "RedirectSuccess")
                else -> MockResponse(404)
            }
        }

        val res = MetadataHttpClient.get("$baseUrl/hop1")
        assertEquals(200, res.code)
        assertEquals("RedirectSuccess", res.body)
    }

    @Test
    fun `refuses cross-origin redirects`() {
        handler = {
            MockResponse(302, headers = mapOf("Location" to "https://evil.example.com/steal"))
        }

        val res = MetadataHttpClient.get("$baseUrl/redirect-external")
        // Did not follow cross-origin: response code is 302, body is null
        assertEquals(302, res.code)
        assertNull(res.body)
    }

    @Test
    fun `stops after 3 hops to prevent infinite redirect loops`() {
        handler = {
            MockResponse(302, headers = mapOf("Location" to "$baseUrl/loop"))
        }

        val res = MetadataHttpClient.get("$baseUrl/loop")
        assertEquals(302, res.code)
        assertNull(res.body)
    }

    @Test
    fun `unhandled network error returns negative one code without throwing`() {
        // Port 1 is closed
        val res = MetadataHttpClient.get("http://127.0.0.1:1/nonexistent")
        assertEquals(-1, res.code)
        assertNull(res.body)
        assertFalse(res.isSuccessful)
    }

    @Test
    fun `non-200 response returns status code and null body`() {
        handler = {
            MockResponse(404, body = "Not found error")
        }

        val res = MetadataHttpClient.get("$baseUrl/notfound")
        assertEquals(404, res.code)
        assertNull(res.body)
        assertFalse(res.isSuccessful)
    }
}
