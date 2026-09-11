package com.cadence.music.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `equal versions are not newer`() {
        assertFalse(isNewerTag("v1.2.3", "1.2.3"))
    }

    @Test
    fun `higher patch is newer`() {
        assertTrue(isNewerTag("v1.2.4", "1.2.3"))
    }

    @Test
    fun `shorter tag missing trailing zero is equal`() {
        assertFalse(isNewerTag("v1.2", "1.2.0"))
    }

    @Test
    fun `older major is not newer`() {
        assertFalse(isNewerTag("v0.9.9", "1.0.0"))
    }

    @Test
    fun `unparseable either side means no update`() {
        assertFalse(isNewerTag("nightly", "1.2.3"))
        assertFalse(isNewerTag("v1.2.3", "debug"))
    }

    @Test
    fun `picks exact release apk asset`() {
        val assets = listOf(
            ReleaseAsset("cadence-v1.2.4-debug.apk", "https://example.com/d"),
            ReleaseAsset("cadence-v1.2.4-release.apk", "https://example.com/r"),
        )
        assertEquals("https://example.com/r", pickApkAsset(assets, "v1.2.4")?.url)
    }

    @Test
    fun `missing asset returns null`() {
        assertEquals(null, pickApkAsset(listOf(ReleaseAsset("notes.txt", "https://example.com/n")), "v1.2.4"))
    }

    @Test
    fun `blank url asset with matching name is skipped`() {
        val assets = listOf(
            ReleaseAsset("cadence-v1.2.4-release.apk", ""),
            ReleaseAsset("cadence-v1.2.4-release.apk", "https://example.com/r"),
        )
        assertEquals("https://example.com/r", pickApkAsset(assets, "v1.2.4")?.url)
    }

    @Test
    fun `trusted release hosts pass on https`() {
        assertTrue(isTrustedReleaseUrl("https://github.com/MDaV05/cadence/releases/download/v1/apk"))
        assertTrue(isTrustedReleaseUrl("https://objects.githubusercontent.com/x/y.apk"))
        assertTrue(isTrustedReleaseUrl("https://codeload.github.com/x/y.apk"))
        assertTrue(isTrustedReleaseUrl("https://raw.githubusercontent.com/x/y.apk"))
        assertTrue(isTrustedReleaseUrl("HTTPS://GitHub.com/x.apk"))
    }

    @Test
    fun `non-https scheme fails even on trusted host`() {
        assertFalse(isTrustedReleaseUrl("http://github.com/x.apk"))
        assertFalse(isTrustedReleaseUrl("ftp://github.com/x.apk"))
        assertFalse(isTrustedReleaseUrl("file:///sdcard/x.apk"))
        assertFalse(isTrustedReleaseUrl("content://provider/x.apk"))
    }

    @Test
    fun `untrusted hosts fail`() {
        assertFalse(isTrustedReleaseUrl("https://evil.com/cadence.apk"))
        assertFalse(isTrustedReleaseUrl("https://notgithub.com/x"))
        assertFalse(isTrustedReleaseUrl("https://github.com.evil.com/x"))
        assertFalse(isTrustedReleaseUrl("https://objects.githubusercontent.com.example.net/x"))
    }

    @Test
    fun `garbage urls fail`() {
        assertFalse(isTrustedReleaseUrl(""))
        assertFalse(isTrustedReleaseUrl("not a url"))
        assertFalse(isTrustedReleaseUrl("github.com/x.apk"))
    }
}
