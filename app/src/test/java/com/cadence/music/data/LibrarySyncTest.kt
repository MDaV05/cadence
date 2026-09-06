package com.cadence.music.data

import com.cadence.music.data.db.AlbumEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySyncTest {

    private fun album(token: String?) = AlbumEntity(
        sourceId = "jellyfin", serverId = "srv1:alb1", title = "T", remoteCreated = token,
    )

    @Test
    fun `matching token skips the album`() {
        assertTrue(albumUnchanged(album("2024-01-01"), "2024-01-01"))
    }

    @Test
    fun `changed token refetches the album`() {
        assertFalse(albumUnchanged(album("2024-01-01"), "2024-02-02"))
    }

    @Test
    fun `null token never skips an existing album`() {
        // Emby/Jellyfin/Plex provide no change token: null == null must not
        // read as "unchanged", or new tracks in existing albums never sync.
        assertFalse(albumUnchanged(album(null), null))
    }

    @Test
    fun `unknown album is never skipped`() {
        assertFalse(albumUnchanged(null, "2024-01-01"))
    }
}
