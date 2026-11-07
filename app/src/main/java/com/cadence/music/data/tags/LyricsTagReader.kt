package com.cadence.music.data.tags

import android.content.Context
import android.net.Uri
import java.io.InputStream

/**
 * Best-effort embedded-lyrics reader. Supports MP3 (ID3v2.3/v2.4 USLT — where
 * plain and pasted-in LRC text both live), FLAC (VORBIS_COMMENT `LYRICS=`) and
 * MP4/M4A (ilst ©lyr atom). Returns the raw lyric text or null; LRC-shaped
 * text is detected by the caller.
 *
 * ponytail: hand-rolled parser covers the common cases — no SYLT, no ID3v2.2,
 * no 64-bit MP4 boxes; add those if real-world files need them.
 */
object LyricsTagReader {

    // Largest payload ever allocated: lyrics are a few KB. Anything bigger is
    // hostile — reject before ByteArray() so the OOM Error can't crash the scan.
    private const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024

    fun read(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { read(it) }
    } catch (_: Exception) { null }

    /** Parses lyric text from a stream positioned at the 4-byte file magic. */
    internal fun read(s: InputStream): String? {
        val magic = ByteArray(4)
        if (!readFully(s, magic)) return null
        when {
            magic.contentEquals("fLaC".toByteArray()) -> return flac(s)
            String(magic, 0, 3, Charsets.US_ASCII) == "ID3" -> return id3(magic[3].toInt() and 0xFF, s)
        }
        // MP4: bytes 4..7 name the first box, which must be ftyp.
        val next = ByteArray(4)
        if (!readFully(s, next)) return null

        if (String(next, 0, 4, Charsets.US_ASCII) != "ftyp") return null
        val size = be32(magic, 0)
        // The 8 magic+type bytes are consumed; skip the rest of ftyp before walking boxes.
        if (size < 8 || !skipFully(s, size - 8L)) return null
        return mp4(s)
    }

    // ---- FLAC ----

    private fun flac(s: InputStream): String? {
        while (true) {
            val hdr = ByteArray(4)
            if (!readFully(s, hdr)) return null
            val last = hdr[0].toInt() and 0x80 != 0
            val type = hdr[0].toInt() and 0x7F
            val len = be24(hdr, 1)
            if (type == 4) { // VORBIS_COMMENT
                if (len > MAX_PAYLOAD_BYTES) return null
                val data = ByteArray(len)
                if (!readFully(s, data)) return null
                return vorbisLyrics(data)
            }
            if (!skipFully(s, len.toLong())) return null
            if (last) return null
        }
    }

    private fun vorbisLyrics(data: ByteArray): String? {
        var p = 0
        fun le32(): Int {
            val v = (data[p].toInt() and 0xFF) or
                ((data[p + 1].toInt() and 0xFF) shl 8) or
                ((data[p + 2].toInt() and 0xFF) shl 16) or
                ((data[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }
        try {
            // Two statements on purpose: `p += le32()` would read p before
            // le32() advances it, dropping the skip (same trap ReplayGainReader avoids).
            val vendorLen = le32()
            p += vendorLen
            val count = le32()
            repeat(count) {
                val l = le32()
                if (l < 0 || p + l > data.size) return null
                val comment = String(data, p, l, Charsets.UTF_8)
                p += l
                if (comment.startsWith("LYRICS=", ignoreCase = true)) {
                    return comment.substringAfter('=').trim().ifEmpty { null }
                }
            }
        } catch (_: Exception) { }
        return null
    }

    // ---- ID3v2 ----

    private fun id3(majorVersion: Int, s: InputStream): String? {
        if (majorVersion < 3 || majorVersion > 4) return null
        val b = ByteArray(6)
        if (!readFully(s, b)) return null
        val flags = b[1].toInt() and 0xFF
        if (flags and 0x80 != 0) return null // unsynchronisation — bail out
        var remaining = syncsafe(b[2], b[3], b[4], b[5])

        if (flags and 0x40 != 0) { // extended header
            val ext = ByteArray(4)
            if (!readFully(s, ext)) return null
            val n = if (majorVersion >= 4) syncsafe(ext[0], ext[1], ext[2], ext[3]) else be32(ext, 0)
            // v2.4 sizes include the size field itself; v2.3 sizes exclude it.
            val skip = if (majorVersion >= 4) n - 4 else n
            if (skip < 0) return null
            remaining -= skip + 4
            if (!skipFully(s, skip.toLong())) return null
        }

        while (remaining >= 10) {
            val id = ByteArray(4)
            if (!readFully(s, id)) return null
            if (id[0].toInt() == 0) return null // padding
            val sz = ByteArray(4)
            if (!readFully(s, sz)) return null
            val size = if (majorVersion >= 4) syncsafe(sz[0], sz[1], sz[2], sz[3]) else be32(sz, 0)
            if (size <= 0 || size > remaining) return null
            val frameFlags = ByteArray(2)
            if (!readFully(s, frameFlags)) return null

            val decodable = if (majorVersion >= 4) {
                frameFlags[1].toInt() and 0x0F == 0 // unsync/compression/encryption/datalen
            } else {
                frameFlags[1].toInt() and 0xE0 == 0 // compression/encryption/grouping
            }
            if (String(id, Charsets.US_ASCII) == "USLT" && decodable) {
                if (size > MAX_PAYLOAD_BYTES) return null
                val payload = ByteArray(size)
                if (!readFully(s, payload)) return null
                usltText(payload)?.let { return it }
            } else {
                if (!skipFully(s, size.toLong())) return null
            }
            remaining -= 10 + size
        }
        return null
    }

    /** USLT payload: encoding byte, 3-byte language, NUL-terminated descriptor, text. */
    private fun usltText(payload: ByteArray): String? {
        if (payload.size < 5) return null
        val enc = payload[0].toInt() and 0xFF
        val wide = enc == 1 || enc == 2
        // Scan the descriptor terminator in whole characters: a byte-wise scan
        // matches a 00 00 pair straddling the descriptor's last UTF-16 unit and
        // the terminator, leaving the text one byte short (misaligned pairs).
        var p = 4
        if (wide) {
            while (p + 1 < payload.size && !(payload[p].toInt() == 0 && payload[p + 1].toInt() == 0)) p += 2
            p += 2
        } else {
            while (p < payload.size && payload[p].toInt() != 0) p++
            p++
        }
        if (p > payload.size) return null
        return decodeText(payload, p, payload.size, enc).trim().ifEmpty { null }
    }

    private fun decodeText(data: ByteArray, from: Int, to: Int, enc: Int): String = try {
        when (enc) {
            1 -> String(data, from, to - from, Charsets.UTF_16).trimStart('\uFEFF')
            2 -> String(data, from, to - from, Charsets.UTF_16BE)
            3 -> String(data, from, to - from, Charsets.UTF_8)
            else -> String(data, from, to - from, Charsets.ISO_8859_1)
        }
    } catch (_: Exception) { "" }

    // ---- MP4/M4A ----

    // moov > udta > meta (4-byte version/flags) > ilst > ©lyr > data.
    private val CONTAINER_PATH = arrayOf("moov", "udta", "meta", "ilst")

    private fun mp4(s: InputStream): String? {
        while (true) {
            val hdr = ByteArray(8)
            if (!readFully(s, hdr)) return null
            val size = be32(hdr, 0)

            if (size < 8) return null // 0 (to EOF) or 1 (64-bit size): not walking blind
            val body = size - 8
            if (String(hdr, 4, 4, Charsets.US_ASCII) == CONTAINER_PATH[0]) {
                return descend(s, body, 1)
            }
            if (!skipFully(s, body.toLong())) return null
        }
    }

    private fun descend(s: InputStream, size: Int, depth: Int): String? {
        var remaining = size
        while (remaining >= 8) {
            val hdr = ByteArray(8)
            if (!readFully(s, hdr)) return null
            val childSize = be32(hdr, 0)
            if (childSize < 8 || childSize > remaining) return null
            remaining -= childSize
            val body = childSize - 8
            val type = String(hdr, 4, 4, Charsets.US_ASCII)
            when {
                // Past the container path = ilst's children: look for ©lyr.
                depth == CONTAINER_PATH.size && hdr[4] == 0xA9.toByte() && type.endsWith("lyr") ->
                    return lyrData(s, body)
                depth < CONTAINER_PATH.size && type == CONTAINER_PATH[depth] -> {
                    // meta carries a 4-byte version/flags header before children.
                    val inner = if (type == "meta") {
                        if (!skipFully(s, 4)) return null
                        body - 4
                    } else body
                    descend(s, inner, depth + 1)?.let { return it }
                }
                else -> if (!skipFully(s, body.toLong())) return null
            }
        }
        return null
    }

    /** Walks the ilst children for the data box: 4-byte type (1 = UTF-8), 4-byte locale, text. */
    private fun lyrData(s: InputStream, size: Int): String? {
        var remaining = size
        while (remaining >= 16) {
            val hdr = ByteArray(8)
            if (!readFully(s, hdr)) return null
            val childSize = be32(hdr, 0)
            if (childSize < 8 || childSize > remaining) return null
            remaining -= childSize
            val payload = childSize - 8
            if (String(hdr, 4, 4, Charsets.US_ASCII) == "data") {
                if (payload < 8 || payload > MAX_PAYLOAD_BYTES) return null
                val data = ByteArray(payload)
                if (!readFully(s, data)) return null
                return String(data, 8, payload - 8, Charsets.UTF_8).trim().ifEmpty { null }
            }
            if (!skipFully(s, payload.toLong())) return null
        }
        return null
    }

    // ---- shared helpers ----

    private fun readFully(s: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = s.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun skipFully(s: InputStream, n: Long): Boolean {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) return false
            left -= r
        }
        return true
    }

    private fun be24(b: ByteArray, off: Int) =
        ((b[off].toInt() and 0xFF) shl 16) or ((b[off + 1].toInt() and 0xFF) shl 8) or (b[off + 2].toInt() and 0xFF)

    private fun be32(b: ByteArray, off: Int) =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun syncsafe(a: Byte, c: Byte, d: Byte, e: Byte) =
        ((a.toInt() and 0x7F) shl 21) or ((c.toInt() and 0x7F) shl 14) or
            ((d.toInt() and 0x7F) shl 7) or (e.toInt() and 0x7F)
}
