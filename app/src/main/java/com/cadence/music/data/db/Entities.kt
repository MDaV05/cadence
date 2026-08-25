package com.cadence.music.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

data class AlbumGroup(val name: String, val artistName: String, val trackCount: Int)

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
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
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

@Entity(tableName = "artists", indices = [Index("mbid")])
data class ArtistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String,
    val serverId: String,
    val name: String,
    val mbid: String? = null,
    val imageUrl: String? = null,
    val bio: String? = null,
    val fetchedAt: Long? = null,
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

@Entity(tableName = "cache_entries")
data class CacheEntryEntity(
    @PrimaryKey val path: String,
    val trackKey: String,
    val size: Long,
    val lastAccess: Long,
)
