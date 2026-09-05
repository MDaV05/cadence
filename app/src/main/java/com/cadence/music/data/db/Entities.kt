package com.cadence.music.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

data class AlbumGroup(val name: String, val artistName: String, val trackCount: Int, val norm: String = "")

data class PlaylistTrackRow(val row: com.cadence.music.data.db.PlaylistTrackEntity, val track: TrackEntity)

@Entity(
    tableName = "tracks",
    indices = [
        Index("albumKey"),
        Index(value = ["sourceId", "serverId"], unique = true),
    ]
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String,
    val serverId: String,
    val title: String,
    val artistName: String = "",
    val albumName: String = "",
    val albumNorm: String = "",
    val albumKey: String? = null,
    val path: String?,
    val durationMs: Long,
    val trackNumber: Int,
    val replayGainDb: Float? = null,
    // MediaStore ALBUM_ID for local tracks; lets us serve album art offline
    // via content://media/external/audio/albumart without any network call.
    val albumMediaId: Long? = null,
    val playCount: Int = 0,
    val lastPlayed: Long? = null,
    // Server-side starred/favorite (Subsonic); local tracks never star.
    val starred: Boolean = false,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    // User-chosen cover image (file path in filesDir); null = fall back to
    // the first track's album art.
    val coverPath: String? = null,
)

/** User-created theme: accent + background colors for light and dark. */
@Entity(tableName = "custom_themes")
data class CustomThemeEntity(
    @PrimaryKey val name: String,
    val accentLight: Int,
    val accentDark: Int,
    val bgLight: Int,
    val bgDark: Int,
)

@Entity(
    tableName = "playlist_tracks",
    indices = [Index("playlistId"), Index("trackId")],
)
data class PlaylistTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val trackId: Long,
    val position: Int,
)

@Entity(tableName = "albums", indices = [Index(value = ["sourceId", "serverId"], unique = true), Index("mbid")])
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String,
    val serverId: String,
    val title: String,
    val artistName: String = "",
    val year: Int? = null,
    val mbid: String? = null,
    val artPath: String? = null,
    val remoteCreated: String? = null,
)

@Entity(tableName = "lyrics")data class LyricsEntity(
    @PrimaryKey val trackId: Long,
    // Raw .lrc text; empty string = checked and none available (negative cache).
    val syncedLrc: String,
    val fetchedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "artist_info")
data class ArtistInfoEntity(
    @PrimaryKey val name: String,
    val bio: String?,
    val imageUrl: String?,
    val fetchedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "pending_scrobbles")
data class PendingScrobbleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val artist: String,
    val title: String,
    val album: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val trackServerId: String,
    val sourceId: String,
    val status: String,
    val bytesDone: Long = 0,
    val transcode: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
