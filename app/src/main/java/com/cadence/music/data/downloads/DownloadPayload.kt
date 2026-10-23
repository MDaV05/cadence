package com.cadence.music.data.downloads

import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

/**
 * Subsonic servers answer a failed /rest/download with HTTP 200 and a tiny
 * JSON/XML error body. The old worker treated any 2xx with a matching
 * Content-Length as audio, so those bodies became "downloaded" tracks.
 * Everything in this file exists to prove a payload is audio before it is
 * allowed to replace a track's path.
 */

/** Bodies below this are never a useful audio file (128 kbps ≈ 16 KB/s). */
internal const val MIN_AUDIO_BYTES = 2_048L

/** Enough to cover every container magic we recognize plus an XML/JSON prefix. */
internal const val DOWNLOAD_SNIFF_BYTES = 4 * 1024
private const val ERROR_BODY_LIMIT = 64 * 1024

data class RemoteError(val code: Int?, val message: String)

sealed interface PayloadVerdict {
    data class Audio(val extension: String) : PayloadVerdict

    data class Rejected(
        val message: String,
        /** Subsonic code 70 = the id no longer exists on this server. */
        val staleId: Boolean = false,
    ) : PayloadVerdict
}

/**
 * Decides whether a just-downloaded body is audio.
 *
 * [head] holds the first bytes of the file, [totalBytes] its full size.
 * Unknown binary content is accepted when the server claims audio (or sends
 * no content type at all) so an exotic codec is never rejected on a guess;
 * text, JSON, XML, HTML and zip are always rejected.
 */
fun classifyDownloadPayload(
    contentType: String?,
    contentDisposition: String?,
    head: ByteArray,
    totalBytes: Long,
): PayloadVerdict {
    val type = mediaType(contentType)
    val text = head.toString(Charsets.UTF_8)
    val trimmed = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
    val remote = parseSubsonicError(trimmed)

    if (remote != null) {
        return PayloadVerdict.Rejected(
            message = describe(remote),
            staleId = remote.code == 70,
        )
    }
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        return PayloadVerdict.Rejected("server returned JSON instead of audio")
    }
    if (trimmed.startsWith("<")) {
        val message = if (trimmed.contains("<html", ignoreCase = true)) {
            "server returned an HTML page"
        } else {
            "server returned XML instead of audio"
        }
        return PayloadVerdict.Rejected(message)
    }
    if (type != null && type in TEXTUAL_TYPES) {
        return PayloadVerdict.Rejected("server returned $type instead of audio")
    }
    if (totalBytes < MIN_AUDIO_BYTES) {
        return PayloadVerdict.Rejected("short response ($totalBytes bytes)")
    }
    if (looksLikeZip(head)) {
        return PayloadVerdict.Rejected("server returned a zip archive")
    }
    if (looksLikeGzip(head)) {
        return PayloadVerdict.Rejected("server returned a compressed error page")
    }
    if (looksLikeImageOrDocument(head)) {
        return PayloadVerdict.Rejected("server returned a non-audio file")
    }

    val claimedAudio = type == null || type.startsWith("audio/") || type in BINARY_TYPES
    if (!claimedAudio && !isKnownAudioHead(head)) {
        return PayloadVerdict.Rejected("unexpected content type: $type")
    }
    return PayloadVerdict.Audio(
        extension = extensionFor(contentDisposition, type, head),
    )
}

/**
 * The Subsonic error a file contains, when it is one. Null for real audio or
 * an unrecognizable body.\
 */
fun audioFileError(file: File): RemoteError? {
    val head = readHead(file, DOWNLOAD_SNIFF_BYTES) ?: return null
    val text = head.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
    if (!text.startsWith("{") && !text.startsWith("[") && !text.startsWith("<")) return null
    val body = readHead(file, ERROR_BODY_LIMIT)?.toString(Charsets.UTF_8).orEmpty()
    return parseSubsonicError(body)
}

/**
 * Why an existing downloaded file must not be treated as playable, or null
 * when it is (or when we simply can't prove otherwise).
 *
 * Deliberately conservative: only unambiguous non-audio (an error body or a
 * zip) and truncated files are rejected. Unknown binary is left alone so a
 * real track with an exotic container is never deleted.
 */
fun audioFileIssue(file: File): String? {
    if (!file.exists()) return "file is missing"
    if (!file.isFile) return "not a regular file"
    audioFileError(file)?.let { return describe(it) }
    val head = readHead(file, DOWNLOAD_SNIFF_BYTES) ?: return "file is unreadable"
    val text = head.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
    if (text.startsWith("{") || text.startsWith("[") || text.startsWith("<")) {
        return "file is an error page, not audio"
    }
    if (looksLikeZip(head)) return "file is a zip archive, not audio"
    if (looksLikeGzip(head)) return "file is a compressed error page, not audio"
    if (looksLikeImageOrDocument(head)) return "file is not audio"
    if (file.length() < MIN_AUDIO_BYTES) return "file is too small (${file.length()} bytes)"
    return null
}

/** True when a failed download should trigger an album re-fetch (id changed/gone). */
fun isStaleDownloadError(errorText: String?): Boolean =
    errorText != null &&
        ("(code 70)" in errorText || "HTTP 404" in errorText || "HTTP 410" in errorText)

fun readHead(file: File, max: Int = DOWNLOAD_SNIFF_BYTES): ByteArray? = runCatching {
    file.inputStream().use { input ->
        val buf = ByteArray(max)
        var read = 0
        while (read < max) {
            val n = input.read(buf, read, max - read)
            if (n <= 0) break
            read += n
        }
        buf.copyOf(read)
    }
}.getOrNull()

/** True when a (possibly partial) file starts with a recognizable error body. */
fun looksLikeErrorBody(file: File): Boolean {
    val head = readHead(file) ?: return false
    val text = head.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
    return text.startsWith("{") || text.startsWith("[") || text.startsWith("<") ||
        looksLikeZip(head) || looksLikeGzip(head)
}

/** Parses a Subsonic JSON or XML failure body; null when it isn't one. */
fun parseSubsonicError(body: String): RemoteError? {
    val trimmed = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
    if (trimmed.startsWith("{")) {
        val parsed = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        val resp = parsed.optJSONObject("subsonic-response") ?: return null
        if (!resp.optString("status").equals("failed", ignoreCase = true)) return null
        val err = resp.optJSONObject("error")
        return RemoteError(
            code = err?.optInt("code")?.takeIf { it > 0 },
            message = err?.optString("message").orEmpty(),
        )
    }
    if (!trimmed.startsWith("<")) return null
    val failed = Regex("""status\s*=\s*["']failed["']""", RegexOption.IGNORE_CASE)
        .containsMatchIn(trimmed)
    val tag = Regex("""<error\b[^>]*>""", RegexOption.IGNORE_CASE).find(trimmed)?.value
    if (!failed && tag == null) return null
    val code = tag?.let {
        Regex("""\bcode\s*=\s*["'](\d+)["']""").find(it)?.groupValues?.get(1)?.toIntOrNull()
    }
    val message = tag?.let {
        Regex("""\bmessage\s*=\s*["']([^"']*)["']""").find(it)?.groupValues?.get(1).orEmpty()
    }.orEmpty()
    return RemoteError(code, unescapeXml(message))
}

fun describe(error: RemoteError): String {
    val message = error.message.ifBlank { "unknown error" }
    return if (error.code != null) "server error: $message (code ${error.code})"
    else "server error: $message"
}

/** Sanitized, extension-less on-disk name shared by every download file. */
fun downloadBaseName(sourceId: String, serverId: String): String =
    (serverId.ifBlank { sourceId }).replace(Regex("[^A-Za-z0-9_-]"), "_")

/** Conventional on-disk name for a downloaded track, safe against path traversal. */
fun downloadFileName(sourceId: String, serverId: String, extension: String = "audio"): String =
    "${downloadBaseName(sourceId, serverId)}.${safeExtension(extension) ?: "audio"}"

/** MIME hint for a downloaded file; null when the extension is unknown. */
fun mimeForExtension(extension: String): String? =
    MIME_BY_EXTENSION[extension.removePrefix(".").lowercase()]

private fun mediaType(contentType: String?): String? =
    contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

private fun extensionFor(contentDisposition: String?, contentType: String?, head: ByteArray): String {
    extensionFromDisposition(contentDisposition)?.let { return it }
    val byType = when (contentType) {
        "audio/mpeg", "audio/mp3", "audio/mpeg3" -> "mp3"
        "audio/ogg", "application/ogg" -> "ogg"
        "audio/opus" -> "opus"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
        "audio/aac", "audio/aacp" -> "aac"
        "audio/wav", "audio/wave", "audio/x-wav" -> "wav"
        "audio/webm" -> "webm"
        "audio/x-ms-wma" -> "wma"
        "audio/aiff", "audio/x-aiff" -> "aiff"
        "audio/x-ape" -> "ape"
        "audio/x-wavpack" -> "wv"
        "audio/x-tta" -> "tta"
        "audio/amr" -> "amr"
        else -> null
    }
    if (byType != null) return byType
    return when {
        head.startsWithAscii("OggS") -> "ogg"
        head.startsWithAscii("fLaC") -> "flac"
        head.startsWithAscii("ID3") -> "mp3"
        head.startsWithAscii("RIFF") -> "wav"
        isFtyp(head) -> "m4a"
        isMpegSync(head) -> "mp3"
        head.startsWithAscii("FORM") && head.asciiAt(8, 4) == "AIFF" -> "aiff"
        head.startsWithAscii("MAC ") -> "ape"
        head.startsWithAscii("wvpk") -> "wv"
        head.startsWithAscii("TTA1") -> "tta"
        head.startsWithAscii("#!AMR") || head.startsWithAscii("AMR\n") -> "amr"
        isAsf(head) -> "wma"
        else -> "audio"
    }
}

private fun extensionFromDisposition(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val encoded = Regex("""filename\*\s*=\s*(?:UTF-8'')?([^;]+)""", RegexOption.IGNORE_CASE)
        .find(value)?.groupValues?.get(1)?.trim()?.trim('"')
    val plain = Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        .find(value)?.groupValues?.get(1)
        ?: Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.get(1)?.trim()?.trim('"')
    val name = encoded?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: plain
    return name?.substringAfterLast('.', "")?.let(::safeExtension)
}

private fun safeExtension(value: String?): String? =
    value?.removePrefix(".")?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{2,5}")) }

private fun isKnownAudioHead(head: ByteArray): Boolean = when {
    head.startsWithAscii("ID3") -> true
    head.startsWithAscii("OggS") -> true
    head.startsWithAscii("fLaC") -> true
    head.startsWithAscii("RIFF") && head.asciiAt(8, 4) == "WAVE" -> true
    head.startsWithAscii("FORM") && head.asciiAt(8, 4) == "AIFF" -> true
    head.startsWithAscii("MAC ") -> true
    head.startsWithAscii("wvpk") -> true
    head.startsWithAscii("TTA1") -> true
    head.startsWithAscii("#!AMR") -> true
    head.startsWithAscii("AMR\n") -> true
    isAsf(head) -> true
    isFtyp(head) -> true
    isMpegSync(head) -> true
    else -> false
}

private fun looksLikeZip(head: ByteArray): Boolean =
    head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
        (head[2] == 0x03.toByte() || head[2] == 0x05.toByte() || head[2] == 0x07.toByte())

private fun looksLikeGzip(head: ByteArray): Boolean =
    head.size >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()

/** Common page/image/document payloads a proxy may hand back with a 200. */
private fun looksLikeImageOrDocument(head: ByteArray): Boolean =
    head.startsWithBytes(0x89, 0x50, 0x4E, 0x47) || // PNG
        head.startsWithBytes(0xFF, 0xD8, 0xFF) || // JPEG
        head.startsWithBytes(0x47, 0x49, 0x46, 0x38) || // GIF
        head.startsWithBytes(0x25, 0x50, 0x44, 0x46) || // PDF
        (head.asciiAt(0, 4) == "RIFF" && head.asciiAt(8, 4) == "WEBP")

private fun isFtyp(head: ByteArray): Boolean = head.asciiAt(4, 4) == "ftyp"

/** MPEG audio / ADTS AAC frame sync. */
private fun isMpegSync(head: ByteArray): Boolean =
    head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0

private fun isAsf(head: ByteArray): Boolean =
    head.size >= 4 && head[0] == 0x30.toByte() && head[1] == 0x26.toByte() &&
        head[2] == 0xB2.toByte() && head[3] == 0x75.toByte()

private fun ByteArray.startsWithAscii(value: String): Boolean =
    size >= value.length && asciiAt(0, value.length) == value

private fun ByteArray.startsWithBytes(vararg values: Int): Boolean =
    size >= values.size && values.indices.all { this[it] == values[it].toByte() }

private fun ByteArray.asciiAt(offset: Int, length: Int): String? {
    if (offset < 0 || length <= 0 || offset + length > size) return null
    return String(this, offset, length, Charsets.US_ASCII)
}

private fun unescapeXml(value: String): String = value
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")

private val TEXTUAL_TYPES = setOf(
    "application/json",
    "application/xml",
    "application/xhtml+xml",
    "text/html",
    "text/xml",
    "text/plain",
)

private val BINARY_TYPES = setOf(
    "application/octet-stream",
    "binary/octet-stream",
    "application/ogg",
)

private val MIME_BY_EXTENSION = mapOf(
    "mp3" to "audio/mpeg",
    "ogg" to "audio/ogg",
    "oga" to "audio/ogg",
    "opus" to "audio/opus",
    "flac" to "audio/flac",
    "m4a" to "audio/mp4",
    "mp4" to "audio/mp4",
    "aac" to "audio/aac",
    "wav" to "audio/wav",
    "webm" to "audio/webm",
    "wma" to "audio/x-ms-wma",
    "aiff" to "audio/aiff",
    "ape" to "audio/x-ape",
    "wv" to "audio/x-wavpack",
    "tta" to "audio/x-tta",
    "amr" to "audio/amr",
)
