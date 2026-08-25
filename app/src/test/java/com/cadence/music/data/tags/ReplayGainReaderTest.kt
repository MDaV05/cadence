package com.cadence.music.data.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ReplayGainReaderTest {

    private fun syncsafe(n: Int): ByteArray = byteArrayOf(
        ((n shr 21) and 0x7F).toByte(),
        ((n shr 14) and 0x7F).toByte(),
        ((n shr 7) and 0x7F).toByte(),
        (n and 0x7F).toByte(),
    )

    private fun be32(n: Int): ByteArray = byteArrayOf(
        ((n shr 24) and 0xFF).toByte(),
        ((n shr 16) and 0xFF).toByte(),
        ((n shr 8) and 0xFF).toByte(),
        (n and 0xFF).toByte(),
    )

    private fun be24(n: Int): ByteArray = byteArrayOf(
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

    private fun txxxPayload(desc: String, value: String, encoding: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encoding)
        when (encoding) {
            1 -> { // UTF-16 with BOM
                out.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
                out.write(desc.toByteArray(Charsets.UTF_16LE))
                out.write(0); out.write(0)
                out.write(value.toByteArray(Charsets.UTF_16LE))
            }
            else -> {
                out.write(desc.toByteArray(Charsets.ISO_8859_1))
                out.write(0)
                out.write(value.toByteArray(Charsets.ISO_8859_1))
            }
        }
        return out.toByteArray()
    }

    private fun id3Stream(major: Int, frames: List<Pair<String, ByteArray>>, extendedHeaderBytes: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray())
        out.write(major); out.write(0)
        var flags = 0
        if (extendedHeaderBytes != null) flags = flags or 0x40
        out.write(flags)
        val body = ByteArrayOutputStream()
        extendedHeaderBytes?.let { body.write(it) }
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

    @Test
    fun `flac vorbis comment gain`() {
        val gain = ReplayGainReader.read(ByteArrayInputStream(flacStream("REPLAYGAIN_TRACK_GAIN=-7.89 dB")))
        assertEquals(-7.89f, gain!!, 0.001f)
    }

    @Test
    fun `flac case insensitive tag and plus sign`() {
        val gain = ReplayGainReader.read(ByteArrayInputStream(flacStream("replaygain_track_gain=+3.10 dB")))
        assertEquals(3.10f, gain!!, 0.001f)
    }

    @Test
    fun `flac ignores unrelated comments`() {
        val gain = ReplayGainReader.read(
            ByteArrayInputStream(flacStream("TITLE=Song", "REPLAYGAIN_TRACK_GAIN=-2.00 dB")),
        )
        assertEquals(-2.00f, gain!!, 0.001f)
    }

    @Test
    fun `flac without gain tag returns null`() {
        assertNull(ReplayGainReader.read(ByteArrayInputStream(flacStream("TITLE=Song"))))
    }

    @Test
    fun `truncated flac returns null`() {
        val full = flacStream("REPLAYGAIN_TRACK_GAIN=-1.00 dB")
        assertNull(ReplayGainReader.read(ByteArrayInputStream(full.copyOf(full.size - 8))))
    }

    @Test
    fun `non audio magic returns null`() {
        assertNull(ReplayGainReader.read(ByteArrayInputStream("RIFFxxxx".toByteArray())))
        assertNull(ReplayGainReader.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `id3v2_3 txxx latin1`() {
        val s = id3Stream(3, listOf("TXXX" to txxxPayload("replaygain_track_gain", "-6.20 dB")))
        assertEquals(-6.20f, ReplayGainReader.read(ByteArrayInputStream(s))!!, 0.001f)
    }

    @Test
    fun `id3v2_4 txxx utf16`() {
        val s = id3Stream(4, listOf("TXXX" to txxxPayload("replaygain_track_gain", "-4.50 dB", encoding = 1)))
        assertEquals(-4.50f, ReplayGainReader.read(ByteArrayInputStream(s))!!, 0.001f)
    }

    @Test
    fun `id3v2_4 extended header regression`() {
        // v2.4 extended header whose size field includes itself: total 6 bytes
        // (4-byte size + 2 filler). Regression test for the off-by-4 skip bug.
        val ext = syncsafe(6) + byteArrayOf(0x00, 0x00)
        val s = id3Stream(4, listOf("TXXX" to txxxPayload("replaygain_track_gain", "-9.99 dB")), ext)
        assertEquals(-9.99f, ReplayGainReader.read(ByteArrayInputStream(s))!!, 0.001f)
    }

    @Test
    fun `id3v2_3 extended header then frame`() {
        // v2.3 ext header size excludes the size field: 4 bytes of content follow.
        val ext = be32(4) + byteArrayOf(0x00, 0x00, 0x00, 0x00)
        val s = id3Stream(3, listOf("TXXX" to txxxPayload("replaygain_track_gain", "+1.25 dB")), ext)
        assertEquals(1.25f, ReplayGainReader.read(ByteArrayInputStream(s))!!, 0.001f)
    }

    @Test
    fun `id3 skips non-txxx frames before txxx`() {
        val junkFrame = "JUNK" to "irrelevant".toByteArray(Charsets.ISO_8859_1)
        val s = id3Stream(3, listOf(junkFrame, "TXXX" to txxxPayload("replaygain_track_gain", "-3.33 dB")))
        assertEquals(-3.33f, ReplayGainReader.read(ByteArrayInputStream(s))!!, 0.001f)
    }

    @Test
    fun `id3 unsynchronised flag bails out`() {
        val s = id3Stream(3, listOf("TXXX" to txxxPayload("replaygain_track_gain", "-1.00 dB")))
        s[5] = 0x80.toByte()
        assertNull(ReplayGainReader.read(ByteArrayInputStream(s)))
    }

    @Test
    fun `id3 without gain frame returns null`() {
        val s = id3Stream(3, listOf("TITL" to "hello".toByteArray()))
        assertNull(ReplayGainReader.read(ByteArrayInputStream(s)))
    }
}
