package com.cadence.music.data.metadata

import org.json.JSONObject

object MusicBrainz {

    private const val BASE = "https://musicbrainz.org/ws/2"

    /** Escape lucene clause characters: backslash first, then double quote. */
    internal fun escapeLucene(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    fun searchReleaseGroup(artist: String, album: String): String? {
        val query = "artist:\"${escapeLucene(artist)}\" AND releasegroup:\"${escapeLucene(album)}\""
        val url = "$BASE/release-group?query=${MetadataHttpClient.encode(query)}&limit=1&fmt=json"
        MusicBrainzRateLimiter.acquire()
        val response = MetadataHttpClient.get(url)
        val body = if (response.code == 200) response.body else null
        if (body == null) return null
        val groups = JSONObject(body).optJSONArray("release-groups") ?: return null
        val id = groups.optJSONObject(0)?.optString("id")
        return id?.ifBlank { null }
    }

    /** Cover Art Archive serves images directly by release-group MBID. */
    fun coverArtUrl(releaseGroupMbid: String): String =
        "https://coverartarchive.org/release-group/$releaseGroupMbid/front-250"
}
