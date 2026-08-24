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
)

data class Album(
    val key: String,
    val sourceId: String,
    val title: String,
    val artist: String,
    val year: Int?,
    val remoteCreated: String? = null,
)
