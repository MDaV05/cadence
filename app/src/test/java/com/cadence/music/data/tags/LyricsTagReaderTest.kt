package com.cadence.music.data.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Pure byte-array fixtures only — no network, no Android. */
class LyricsTagReaderTest {

    // -- fixture builders (same shape as ReplayGainReaderTest) --

    private fun be24(n: Int): ByteArray = byteArrayOf(
        ((n shr 16) and 0xFF).toByte(),
        ((n shr 8) and 0xFF).toByte(),
        (n and 0xFF).toByte(),
    )

    private fun be32(n: Int): ByteArray = byteArrayOf(
        ((n shr 24) and 0xFF).toByte(),
        ((n shr 16) and 0xFF).toByte(),
        ((n shr 8) and 0xFF).toByte(),
        (n and 0xFF).toByte(),
    )

    private fun le32(n: Int): ByteArray = byteArrayOf(
        (n and 0xFF).toByte(),
        ((n shr 8) and 0xFF).toByte(),
        ((n shr 16) and 0xFF).toByte(),
        ((n shr 24) and 0xFF).toByte(),
    )

    private fun syncsafe(n: Int): ByteArray = byteArrayOf(
        ((n shr 21) and 0x7F).toByte(),
        ((n shr 14) and 0x7F).toByte(),
        ((n shr 7) and 0x7F).toByte(),
        (n and 0x7F).toByte(),
    )

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    private fun flacStream(vararg comments: String): ByteArray {
        val vendor = "test".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        val data = ByteArrayOutputStream()
        data.write(le32(vendor.size))
        data.write(vendor)
        data.write(le32(comments.size))
        for (c in comments) {
            val b = c.toByteArray(Charsets.UTF_8)
            data.write(le32(b.size))
            data.write(b)
        }
        val payload = data.toByteArray()
        // block header: last-flag set, type 4 (VORBIS_COMMENT), 3-byte length
        out.write(0x80 or 4)
        out.write(be24(payload.size))
        out.write(payload)
        return out.toByteArray()
    }

    private fun usltPayload(desc: String, text: String, encoding: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encoding)
        out.write(ascii("eng"))
        when (encoding) {
            1 -> { // UTF-16 with BOM
                out.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
                out.write(desc.toByteArray(Charsets.UTF_16LE))
                out.write(0); out.write(0)
                out.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
                out.write(text.toByteArray(Charsets.UTF_16LE))
            }
            else -> {
                out.write(desc.toByteArray(Charsets.ISO_8859_1))
                out.write(0)
                out.write(text.toByteArray(Charsets.ISO_8859_1))
            }
        }
        return out.toByteArray()
    }

    private fun id3Stream(major: Int, frames: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray())
        out.write(major); out.write(0)
        out.write(0) // no tag flags
        val body = ByteArrayOutputStream()
        for ((id, payload) in frames) {
            require(id.length == 4)
            body.write(id.toByteArray(Charsets.US_ASCII))
            body.write(if (major >= 4) syncsafe(payload.size) else be32(payload.size))
            body.write(0); body.write(0) // frame flags
            body.write(payload)
        }
        val b = body.toByteArray()
        out.write(syncsafe(b.size))
        out.write(b)
        return out.toByteArray()
    }

    private fun box(type: ByteArray, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { a, b -> a + b }
        val out = ByteArrayOutputStream()
        out.write(be32(body.size + 8))
        out.write(type)
        out.write(body)
        return out.toByteArray()
    }

    private val lyrType = byteArrayOf(0xA9.toByte()) + ascii("lyr")

    private fun mp4Stream(lyrics: String?): ByteArray {
        val ftyp = box(ascii("ftyp"), ascii("M4A "), le32(0))
        val children = if (lyrics == null) {
            emptyArray()
        } else {
            arrayOf(
                box(
                    lyrType,
                    box(ascii("data"), le32(1), le32(0), lyrics.toByteArray(Charsets.UTF_8)),
                ),
            )
        }
        val moov = box(
            ascii("moov"),
            box(ascii("udta"), box(ascii("meta"), le32(0), box(ascii("ilst"), *children))),
        )
        return ftyp + moov
    }

    // -- tests --

    @Test
    fun `flac lyrics comment`() {
        val text = LyricsTagReader.read(ByteArrayInputStream(flacStream("LYRICS=first\nsecond")))
        assertEquals("first\nsecond", text)
    }

    @Test
    fun `flac case insensitive tag and trim`() {
        val text = LyricsTagReader.read(ByteArrayInputStream(flacStream("lyrics=  spaced out  ")))
        assertEquals("spaced out", text)
    }

    @Test
    fun `flac without lyrics returns null`() {
        assertNull(LyricsTagReader.read(ByteArrayInputStream(flacStream("TITLE=Song"))))
    }

    @Test
    fun `id3v2_3 uslt with descriptor`() {
        val s = id3Stream(3, listOf("USLT" to usltPayload("desc", "la la")))
        assertEquals("la la", LyricsTagReader.read(ByteArrayInputStream(s)))
    }

    @Test
    fun `id3v2_4 uslt utf16 lrc without descriptor`() {
        val s = id3Stream(4, listOf("USLT" to usltPayload("", "[00:01.00]hello", encoding = 1)))
        assertEquals("[00:01.00]hello", LyricsTagReader.read(ByteArrayInputStream(s)))
    }

    @Test
    fun `id3 skips junk frames before uslt`() {
        val junk = "JUNK" to "irrelevant".toByteArray(Charsets.ISO_8859_1)
        val s = id3Stream(3, listOf(junk, "USLT" to usltPayload("", "found")))
        assertEquals("found", LyricsTagReader.read(ByteArrayInputStream(s)))
    }

    @Test
    fun `id3 without uslt returns null`() {
        val s = id3Stream(3, listOf("TIT2" to "hello".toByteArray(Charsets.ISO_8859_1)))
        assertNull(LyricsTagReader.read(ByteArrayInputStream(s)))
    }

    @Test
    fun `mp4 lyr atom`() {
        assertEquals("m4a words", LyricsTagReader.read(ByteArrayInputStream(mp4Stream("m4a words"))))
    }

    @Test
    fun `mp4 without lyr atom returns null`() {
        assertNull(LyricsTagReader.read(ByteArrayInputStream(mp4Stream(null))))
    }

    @Test
    fun `unknown magic and empty stream return null`() {
        assertNull(LyricsTagReader.read(ByteArrayInputStream("RIFFxxxx".toByteArray())))
        assertNull(LyricsTagReader.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `oversized uslt frame is rejected without allocation`() {
        // >4MB payload: must return null (cap), never allocate the frame.
        val big = "[00:01.00]" + "x".repeat(5 * 1024 * 1024)
        val s = id3Stream(3, listOf("USLT" to usltPayload("", big)))
        assertNull(LyricsTagReader.read(ByteArrayInputStream(s)))
    }
}
