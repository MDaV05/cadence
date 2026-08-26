package com.cadence.music.data.source

data class Track(
    val key: String,
    val sourceId: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumKey: String? = null,
    val durationMs: Long,
    val localPath: String?,
    val streamUrl: String? = null,
    // MediaStore ALBUM_ID for local tracks (offline album art).
    val albumMediaId: Long? = null,
    // Server-side starred/favorite state (Subsonic only).
    val starred: Boolean = false,
)

data class Album(
    val key: String,
    val sourceId: String,
    val title: String,
    val artist: String,
    val year: Int?,
    val remoteCreated: String? = null,
)
