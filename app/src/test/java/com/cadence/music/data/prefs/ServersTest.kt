package com.cadence.music.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServersTest {

    @Test
    fun `entry json round-trips all fields`() {
        val e = ServerEntry(
            id = "a1", type = ServerType.JELLYFIN, url = "https://box:8096",
            user = "u", password = null, token = "tok", userId = "uid1", active = true,
        )
        assertEquals(e, ServerEntry.fromJson(e.toJson()))
    }

    @Test
    fun `nulls survive round-trip`() {
        val e = ServerEntry("p", ServerType.SUBSONIC, "http://h", "u", "pw", null, null, false)
        val back = ServerEntry.fromJson(e.toJson())
        assertEquals(null, back.token)
        assertEquals(null, back.userId)
        assertEquals(false, back.active)
    }

    @Test
    fun `type parses strictly`() {
        assertEquals(ServerType.PLEX, ServerType.valueOf("PLEX"))
        assertNull(runCatching { ServerType.valueOf("plex") }.getOrNull())
    }
}
