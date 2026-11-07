package com.cadence.music.data.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadErrorsTest {
    @Test fun `missing error is a plain failure`() {
        assertEquals("Download failed", humanizeDownloadError(null))
        assertEquals("Download failed", humanizeDownloadError("  "))
    }

    @Test fun `missing track is explained as removed`() {
        assertEquals(
            "This track moved or was removed on the server",
            humanizeDownloadError("HTTP 404"),
        )
        assertEquals(
            "This track moved or was removed on the server",
            humanizeDownloadError("server said (code 70)"),
        )
    }

    @Test fun `auth failures point at account settings`() {
        assertTrue(humanizeDownloadError("HTTP 401").contains("sign-in"))
        assertTrue(humanizeDownloadError("HTTP 403 Forbidden").contains("sign-in"))
        assertTrue(humanizeDownloadError("server error: Wrong password (code 40)").contains("sign-in"))
    }

    @Test fun `storage pressure is named honestly`() {
        assertEquals(
            "Not enough free space on this device",
            humanizeDownloadError("not enough free storage"),
        )
    }

    @Test fun `network failures are distinguishable`() {
        assertEquals("The connection timed out", humanizeDownloadError("connect timed out"))
        assertEquals("Couldn't reach the server", humanizeDownloadError("unknown host"))
        assertEquals("The server is having trouble. Try again later", humanizeDownloadError("HTTP 503"))
        assertEquals("The download was interrupted", humanizeDownloadError("truncated: 4 of 8 bytes"))
    }

    @Test fun `an error page is not reported as audio`() {
        assertEquals(
            "The server sent a web page instead of audio",
            humanizeDownloadError("content-type was JSON instead of audio"),
        )
    }

    @Test fun `unknown errors keep their diagnostic text`() {
        assertEquals("weird failure", humanizeDownloadError("weird failure"))
    }
}
