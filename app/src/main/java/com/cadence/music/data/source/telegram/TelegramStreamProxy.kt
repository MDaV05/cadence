package com.cadence.music.data.source.telegram

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

class TelegramStreamProxy private constructor(private val manager: TelegramManager) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    val port: Int
        get() = serverSocket?.localPort ?: 0

    @Synchronized
    fun start() {
        if (isRunning.getAndSet(true)) return

        try {
            val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            scope.launch {
                while (isRunning.get() && !ss.isClosed) {
                    try {
                        val client = ss.accept()
                        scope.launch { handleClient(client) }
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local Telegram stream proxy: ${e.message}", e)
            isRunning.set(false)
        }
    }

    fun streamUrl(remoteFileId: String): String {
        start()
        return "http://127.0.0.1:$port/stream?remoteId=" + URLEncoder.encode(remoteFileId, "UTF-8")
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 30_000
            val input = s.getInputStream()
            val output = s.getOutputStream()

            val lines = mutableListOf<String>()
            val reader = input.bufferedReader(Charsets.ISO_8859_1)
            var line: String? = reader.readLine()
            val requestLine = line ?: return
            while (!line.isNullOrEmpty()) {
                lines.add(line)
                line = reader.readLine()
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            if (path.startsWith("/stream")) {
                handleStreamRequest(method, path, lines, output)
            } else if (path.startsWith("/thumb")) {
                handleThumbRequest(path, output)
            } else {
                sendNotFound(output)
            }
        }
    }

    private fun handleStreamRequest(
        method: String,
        path: String,
        headers: List<String>,
        output: OutputStream,
    ) {
        val remoteId = parseQueryParam(path, "remoteId") ?: return
        try {
            val file = runBlocking { manager.getRemoteFile(remoteId) }
            val fileId = file.id

            var totalSize = if (file.size > 0) file.size else file.expectedSize
            if (totalSize <= 0) {
                // Wait briefly for TDLib to fetch metadata
                val updated = runBlocking {
                    manager.downloadFile(fileId, priority = 32, offset = 0, limit = 1, synchronous = true)
                }
                totalSize = if (updated.size > 0) updated.size else updated.expectedSize
            }
            if (totalSize <= 0) totalSize = 10L * 1024 * 1024 // Fallback 10MB estimate

            var rangeStart = 0L
            var rangeEnd = totalSize - 1
            var isRange = false

            for (h in headers) {
                if (h.startsWith("Range:", ignoreCase = true)) {
                    val rangeVal = h.substringAfter(":").trim()
                    if (rangeVal.startsWith("bytes=")) {
                        val spec = rangeVal.removePrefix("bytes=").split("-")
                        val start = spec.getOrNull(0)?.toLongOrNull()
                        val end = spec.getOrNull(1)?.toLongOrNull()
                        if (start != null) {
                            rangeStart = start
                            isRange = true
                        }
                        if (end != null) {
                            rangeEnd = end
                            isRange = true
                        }
                    }
                }
            }

            if (rangeStart > rangeEnd || rangeStart >= totalSize) {
                sendRangeNotSatisfiable(output, totalSize)
                return
            }

            val contentLength = rangeEnd - rangeStart + 1

            // Request TDLib to start downloading from rangeStart
            runBlocking {
                manager.downloadFile(fileId, priority = 32, offset = rangeStart, limit = 0, synchronous = false)
            }

            val statusLine = if (isRange) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
            val responseHeaders = buildString {
                append(statusLine)
                append("Content-Type: audio/mpeg\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $contentLength\r\n")
                if (isRange) {
                    append("Content-Range: bytes $rangeStart-$rangeEnd/$totalSize\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            output.write(responseHeaders.toByteArray(Charsets.ISO_8859_1))
            output.flush()

            if (method.equals("HEAD", ignoreCase = true)) return

            // Stream audio bytes progressively as TDLib downloads them
            var position = rangeStart
            val buffer = ByteArray(64 * 1024)
            val deadline = System.currentTimeMillis() + 120_000

            while (position <= rangeEnd) {
                val currentFile = runBlocking { manager.getFile(fileId) }
                val downloadedPrefix = runBlocking { manager.getDownloadedPrefix(fileId, position) }
                val localPath = currentFile.local.path

                if (downloadedPrefix > 0 && localPath.isNotEmpty()) {
                    val available = downloadedPrefix
                    val toRead = minOf(buffer.size.toLong(), rangeEnd - position + 1, available).toInt()
                    if (toRead > 0) {
                        RandomAccessFile(localPath, "r").use { raf ->
                            raf.seek(position)
                            val read = raf.read(buffer, 0, toRead)
                            if (read > 0) {
                                output.write(buffer, 0, read)
                                output.flush()
                                position += read
                            }
                        }
                        continue
                    }
                }

                if (currentFile.local.isDownloadingCompleted && localPath.isNotEmpty()) {
                    val remaining = rangeEnd - position + 1
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    if (toRead > 0) {
                        RandomAccessFile(localPath, "r").use { raf ->
                            raf.seek(position)
                            val read = raf.read(buffer, 0, toRead)
                            if (read > 0) {
                                output.write(buffer, 0, read)
                                output.flush()
                                position += read
                            } else {
                                break
                            }
                        }
                        continue
                    } else {
                        break
                    }
                }

                if (System.currentTimeMillis() > deadline) {
                    Log.w(TAG, "Streaming timed out waiting for TDLib chunk at offset $position")
                    break
                }
                Thread.sleep(60)
            }
        } catch (e: Exception) {
            // Socket closed by ExoPlayer or client skipped — expected
        }
    }

    private fun handleThumbRequest(path: String, output: OutputStream) {
        val fileIdStr = parseQueryParam(path, "fileId")
        val fileId = fileIdStr?.toIntOrNull() ?: run {
            sendNotFound(output)
            return
        }
        val file = runBlocking { runCatching { manager.getFile(fileId) }.getOrNull() }
        val localPath = file?.local?.path
        if (localPath != null && File(localPath).exists()) {
            val bytes = File(localPath).readBytes()
            val header = "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.size}\r\n\r\n"
            output.write(header.toByteArray(Charsets.ISO_8859_1))
            output.write(bytes)
            output.flush()
        } else {
            sendNotFound(output)
        }
    }

    private fun sendRangeNotSatisfiable(output: OutputStream, totalSize: Long) {
        val resp = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$totalSize\r\n\r\n"
        output.write(resp.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun sendNotFound(output: OutputStream) {
        val resp = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
        output.write(resp.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun parseQueryParam(url: String, name: String): String? {
        val query = url.substringAfter("?", "")
        for (pair in query.split("&")) {
            val parts = pair.split("=")
            if (parts.size == 2 && parts[0] == name) {
                return URLDecoder.decode(parts[1], "UTF-8")
            }
        }
        return null
    }

    companion object {
        private const val TAG = "TelegramStreamProxy"

        @Volatile private var instance: TelegramStreamProxy? = null

        fun get(context: Context): TelegramStreamProxy =
            instance ?: synchronized(this) {
                instance ?: TelegramStreamProxy(TelegramManager.get(context)).also {
                    instance = it
                    it.start()
                }
            }
    }
}
