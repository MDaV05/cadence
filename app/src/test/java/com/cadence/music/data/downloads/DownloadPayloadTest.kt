package com.cadence.music.data.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadPayloadTest {

    private val navidromeError =
        """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":70,"message":"data not found"}}}"""

    private fun bytes(vararg head: Int, size: Int = 4096): ByteArray {
        val out = ByteArray(size)
        head.forEachIndexed { i, v -> out[i] = v.toByte() }
        return out
    }

    private fun verdict(type: String?, head: ByteArray, size: Long = head.size.toLong()) =
        classifyDownloadPayload(type, null, head, size)

    @Test
    fun `navidrome json error is rejected and flagged stale`() {
        val v = verdict("application/json", navidromeError.toByteArray()) as PayloadVerdict.Rejected
        assertEquals("server error: data not found (code 70)", v.message)
        assertTrue(v.staleId)
    }

    @Test
    fun `json error is caught even when the server claims audio`() {
        val v = verdict("audio/mpeg", navidromeError.toByteArray(), 4096) as PayloadVerdict.Rejected
        assertTrue(v.staleId)
    }

    @Test
    fun `xml subsonic error is parsed`() {
        val xml = """<subsonic-response status="failed"><error code="10" message="Required parameter is missing"/></subsonic-response>"""
        val v = verdict("text/xml", xml.toByteArray()) as PayloadVerdict.Rejected
        assertEquals("server error: Required parameter is missing (code 10)", v.message)
        assertEquals(false, v.staleId)
    }

    @Test
    fun `html login page is rejected`() {
        val v = verdict("text/html", "<html><body>login</body></html>".toByteArray()) as PayloadVerdict.Rejected
        assertTrue(v.message.contains("HTML"))
    }

    @Test
    fun `tiny body is rejected before any sniffing`() {
        val v = verdict("audio/mpeg", bytes(size = 100)) as PayloadVerdict.Rejected
        assertTrue(v.message.startsWith("short response"))
    }

    @Test
    fun `zip archive is rejected`() {
        val v = verdict("application/zip", bytes(0x50, 0x4B, 0x03, 0x04)) as PayloadVerdict.Rejected
        assertTrue(v.message.contains("zip"))
    }

    @Test
    fun `gzip error page is rejected`() {
        val v = verdict("application/octet-stream", bytes(0x1F, 0x8B, 0x08, 0x00)) as PayloadVerdict.Rejected
        assertTrue(v.message.contains("compressed"))
    }

    @Test
    fun `image payload is rejected`() {
        val v = verdict("audio/mpeg", bytes(0x89, 0x50, 0x4E, 0x47)) as PayloadVerdict.Rejected
        assertTrue(v.message.contains("non-audio"))
    }

    @Test
    fun `audio file issue rejects gzip and images`() {
        val gz = File.createTempFile("cadence", ".mp3")
        val png = File.createTempFile("cadence", ".mp3")
        try {
            gz.writeBytes(bytes(0x1F, 0x8B, 0x08, 0x00))
            png.writeBytes(bytes(0x89, 0x50, 0x4E, 0x47))
            assertTrue(audioFileIssue(gz)!!.contains("compressed"))
            assertTrue(audioFileIssue(png)!!.contains("not audio"))
        } finally {
            gz.delete()
            png.delete()
        }
    }

    @Test
    fun `looksLikeErrorBody recognizes gzip error bodies`() {
        val file = File.createTempFile("cadence", ".part")
        try {
            file.writeBytes(bytes(0x1F, 0x8B, 0x08, 0x00))
            assertTrue(looksLikeErrorBody(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `stale download errors include subsonic 70 and http 404 410`() {
        assertTrue(isStaleDownloadError("server error: data not found (code 70)"))
        assertTrue(isStaleDownloadError("HTTP 404"))
        assertTrue(isStaleDownloadError("HTTP 410"))
        assertEquals(false, isStaleDownloadError(null))
        assertEquals(false, isStaleDownloadError("HTTP 500"))
    }

    @Test
    fun `id3 mp3 is accepted with an mp3 extension`() {
        val v = verdict("audio/mpeg", bytes(0x49, 0x44, 0x33, 0x03)) as PayloadVerdict.Audio
        assertEquals("mp3", v.extension)
    }

    @Test
    fun `ogg flac and m4a magics are recognized without a content type`() {
        assertEquals("ogg", (verdict(null, bytes(0x4F, 0x67, 0x67, 0x53)) as PayloadVerdict.Audio).extension)
        assertEquals("flac", (verdict(null, bytes(0x66, 0x4C, 0x61, 0x43)) as PayloadVerdict.Audio).extension)
        assertEquals(
            "m4a",
            (verdict(null, bytes(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70)) as PayloadVerdict.Audio).extension,
        )
    }

    @Test
    fun `mpeg sync is recognized as mp3`() {
        val v = verdict("application/octet-stream", bytes(0xFF, 0xFB, 0x90, 0x64)) as PayloadVerdict.Audio
        assertEquals("mp3", v.extension)
    }

    @Test
    fun `content disposition wins for transcoded opus`() {
        val v = classifyDownloadPayload(
            "audio/ogg", """attachment; filename="track.opus"""", bytes(0x4F, 0x67, 0x67, 0x53), 4096,
        ) as PayloadVerdict.Audio
        assertEquals("opus", v.extension)
    }

    @Test
    fun `unknown binary with a non-audio content type is rejected`() {
        val v = verdict("application/pdf", bytes(0x25, 0x50, 0x44, 0x46)) as PayloadVerdict.Rejected
        assertTrue(v.message.contains("non-audio"))
    }

    @Test
    fun `unknown binary from an audio server is accepted`() {
        val v = verdict("audio/x-ape", bytes(0x01, 0x02, 0x03, 0x04)) as PayloadVerdict.Audio
        assertEquals("ape", v.extension)
    }

    @Test
    fun `audio file issue detects an error page`() {
        val f = File.createTempFile("cadence", ".audio")
        try {
            f.writeBytes(navidromeError.toByteArray())
            val issue = audioFileIssue(f)
            assertTrue(issue != null && issue.contains("data not found"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `audio file issue detects truncated file`() {
        val f = File.createTempFile("cadence", ".mp3")
        try {
            f.writeBytes(ByteArray(100))
            val issue = audioFileIssue(f)
            assertTrue(issue != null && issue.contains("too small"))
        } finally {
            f.delete()
        }
    }
}
