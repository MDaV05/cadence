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
}
