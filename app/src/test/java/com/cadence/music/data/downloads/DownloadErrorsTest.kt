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

    // ---- cause classification (spec D8) ----

    @Test fun `every cause has its group title`() {
        assertEquals("Connection problem", groupTitle(DownloadCause.NETWORK))
        assertEquals("Sign-in expired", groupTitle(DownloadCause.AUTH))
        assertEquals("Server error", groupTitle(DownloadCause.SERVER))
        assertEquals("Track removed or moved", groupTitle(DownloadCause.NOT_FOUND))
        assertEquals("Not enough space", groupTitle(DownloadCause.STORAGE))
        assertEquals("Download failed", groupTitle(DownloadCause.UNKNOWN))
    }

    @Test fun `connection failures classify as network`() {
        for (text in listOf(
            "unknown host",
            "connect timed out",
            "connection refused",
            "Unable to resolve host \"lan\": No address associated with hostname",
            "Socket closed",
            "failed to connect to /10.0.0.2:443",
            "Network is unreachable",
            "truncated: 4 of 8 bytes",
            "HTTP 408", // request timeout
        )) {
            assertEquals(text, DownloadCause.NETWORK, downloadCause(text))
        }
    }

    @Test fun `rejected credentials classify as auth`() {
        for (text in listOf(
            "HTTP 401",
            "HTTP 403 Forbidden",
            "unauthorized",
            "not authorized for this operation",
            "wrong username or password",
            // describe() prefixes "server error:" — auth must win over SERVER.
            "server error: Wrong password (code 40)",
            "server error: Not authorized (code 50)",
        )) {
            assertEquals(text, DownloadCause.AUTH, downloadCause(text))
        }
    }

    @Test fun `server misbehavior classifies as server`() {
        for (text in listOf(
            "HTTP 503",
            "HTTP 425",
            "HTTP 429",
            "bad redirect",
            // Regression pin: "refused" alone must not read as a network failure.
            "refused a cross-server redirect",
            "unexpected content type: text/html",
            "server returned a zip archive",
            "server returned JSON instead of audio",
            "server returned an HTML page",
            "short response (12 bytes)",
            "file is too small (12 bytes)",
            "server is not configured",
            // Resume desync: the partial no longer matches the server's file.
            "HTTP 416",
            "server resumed at the wrong offset",
        )) {
            assertEquals(text, DownloadCause.SERVER, downloadCause(text))
        }
    }

    @Test fun `missing tracks classify as not found`() {
        for (text in listOf(
            "HTTP 404",
            "HTTP 410",
            "server said (code 70)",
            // describe() output with a Subsonic 70: NOT_FOUND beats "server error:".
            "server error: The requested track was not found (code 70)",
        )) {
            assertEquals(text, DownloadCause.NOT_FOUND, downloadCause(text))
        }
    }

    @Test fun `device storage problems classify as storage`() {
        for (text in listOf(
            "not enough free storage",
            "No space left on device",
            "could not read downloaded file",
            "could not finalize file",
            "file is unreadable",
        )) {
            assertEquals(text, DownloadCause.STORAGE, downloadCause(text))
        }
    }

    @Test fun `unrecognized errors stay unknown`() {
        assertEquals(DownloadCause.UNKNOWN, downloadCause(null))
        assertEquals(DownloadCause.UNKNOWN, downloadCause("  "))
        assertEquals(DownloadCause.UNKNOWN, downloadCause("weird failure"))
        assertEquals(DownloadCause.UNKNOWN, downloadCause("download rejected"))
        assertEquals(DownloadCause.UNKNOWN, downloadCause("file is missing"))
    }
}
