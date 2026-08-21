package com.cadence.music.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracks",
    indices = [Index("albumId"), Index("artistId"), Index("sourceId", "serverId")]
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String,
    val serverId: String,
    val title: String,
    val artistId: Long?,
    val albumId: Long?,
    val path: String?,
    val durationMs: Long,
    val trackNumber: Int,
    val replayGainDb: Float? = null,
    val playCount: Int = 0,
    val lastPlayed: Long? = null,
)

@Entity(tableName = "albums", indices = [Index("mbid")])
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String,
    val serverId: String,
    val title: String,
    val artistId: Long?,
    val year: Int?,
    val mbid: String? = null,
    val artPath: String? = null,
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
