package com.cadence.music.data.source

interface MusicSource {
    val id: String

    suspend fun listAlbums(): List<Album> = emptyList()
    suspend fun albumTracksByKey(albumKey: String): List<Track> = emptyList()
    suspend fun streamUrl(track: Track): String?
    suspend fun coverArtUrl(albumKey: String): String? = null
    fun downloadUrl(songId: String, format: String = "", bitrate: Int = 0): String? = null
    suspend fun setStarred(songId: String, starred: Boolean) {}
    suspend fun ping(): Boolean = true

    suspend fun scan(): List<Track> = emptyList()
    suspend fun search(query: String): List<Track> = emptyList()
}
