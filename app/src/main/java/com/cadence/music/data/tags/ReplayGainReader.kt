package com.cadence.music.data.tags

import android.content.Context
import android.net.Uri
import java.io.EOFException
import java.io.InputStream

/**
 * Best-effort ReplayGain track-gain reader. Supports FLAC (VORBIS_COMMENT)
 * and MP3 (ID3v2.3/v2.4 TXXX frames). Returns gain in dB or null.
 *
 * ponytail: hand-rolled parser covers the common cases; swap in a real tag
 * library or Rust parser if exotic files matter.
 */
object ReplayGainReader {

    fun read(context: Context, uri: Uri): Float? = try {
        context.contentResolver.openInputStream(uri)?.use { read(it) }
    } catch (_: Exception) { null }

    /** Parses gain from a stream positioned at the 4-byte file magic. */
    internal fun read(s: InputStream): Float? {
        val magic = ByteArray(4)
        if (!readFully(s, magic)) return null
        return when {
            magic.contentEquals("fLaC".toByteArray()) -> flac(s)
            String(magic, 0, 3, Charsets.US_ASCII) == "ID3" -> id3(magic[3].toInt() and 0xFF, s)
            else -> null
        }
    }

    // ---- FLAC ----

    private fun flac(s: InputStream): Float? {
        while (true) {
            val hdr = ByteArray(4)
            if (!readFully(s, hdr)) return null
            val last = hdr[0].toInt() and 0x80 != 0
            val type = hdr[0].toInt() and 0x7F
            val len = be24(hdr, 1)
            if (type == 4) {
                val data = ByteArray(len)
                if (!readFully(s, data)) return null
                return vorbisComment(data)
            }
            if (!skipFully(s, len)) return null
            if (last) return null
        }
    }

    private fun vorbisComment(data: ByteArray): Float? {
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
            val vendorLen = le32()
            p += vendorLen // skip vendor string
            val count = le32()
            repeat(count) {
                val l = le32()
                if (l < 0 || p + l > data.size) return null
                val comment = String(data, p, l, Charsets.UTF_8)
                p += l
                if (comment.startsWith("REPLAYGAIN_TRACK_GAIN=", ignoreCase = true)) {
                    return parseDb(comment.substringAfter('='))
                }
            }
        } catch (_: Exception) { }
        return null
    }

    // ---- ID3v2 ----

    private fun id3(majorVersion: Int, s: InputStream): Float? {
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
            if (!skipFully(s, skip)) return null
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

            val name = String(id, Charsets.US_ASCII)
            if (name == "TXXX") {
                val payload = ByteArray(size)
                if (!readFully(s, payload)) return null
                txxxGain(payload)?.let { return it }
            } else {
                if (!skipFully(s, size)) return null
            }
            remaining -= 10 + size
        }
        return null
    }

    private fun txxxGain(payload: ByteArray): Float? {
        val enc = payload[0].toInt() and 0xFF
        val wide = enc == 1 || enc == 2
        // find description terminator
        var p = 1
        while (p < payload.size) {
            if (wide && p + 1 < payload.size && payload[p].toInt() == 0 && payload[p + 1].toInt() == 0) { p += 2; break }
            if (!wide && payload[p].toInt() == 0) { p += 1; break }
            p++
        }
        if (p >= payload.size) return null
        val desc = decodeText(payload, 1, p - 1, enc)
        if (!desc.contains("replaygain_track_gain", ignoreCase = true)) return null
        val value = decodeText(payload, p, payload.size, enc)
        return parseDb(value)
    }

    private fun decodeText(data: ByteArray, from: Int, to: Int, enc: Int): String = try {
        when (enc) {
            1 -> String(data, from, to - from, Charsets.UTF_16).trimStart('\uFEFF')
            2 -> String(data, from, to - from, Charsets.UTF_16BE)
            3 -> String(data, from, to - from, Charsets.UTF_8)
            else -> String(data, from, to - from, Charsets.ISO_8859_1)
        }
    } catch (_: Exception) { "" }

    // ---- shared helpers ----

    private fun parseDb(raw: String): Float? =
        Regex("[-+]?\\d+(?:\\.\\d+)?").find(raw)?.value?.toFloatOrNull()

    private fun readFully(s: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = s.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun skipFully(s: InputStream, n: Int): Boolean {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = s.read(buf, 0, minOf(buf.size, left))
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
