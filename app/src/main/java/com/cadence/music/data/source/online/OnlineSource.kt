package com.cadence.music.data.source.online

import com.cadence.music.data.source.Track

interface OnlineSource {
    val sourceId: String
    val displayName: String
    suspend fun search(query: String): List<Track>
    suspend fun resolveStreamUrl(key: String): String?
    suspend fun resolveStream(key: String): String? = resolveStreamUrl(key)
    suspend fun coverArtUrl(key: String): String? = null
}
