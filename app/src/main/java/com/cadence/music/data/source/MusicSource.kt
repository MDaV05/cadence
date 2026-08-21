package com.cadence.music.data.source

interface MusicSource {
    val id: String
    suspend fun scan(): List<Track>
    suspend fun search(query: String): List<Track>
    suspend fun streamUrl(track: Track): String?
}
