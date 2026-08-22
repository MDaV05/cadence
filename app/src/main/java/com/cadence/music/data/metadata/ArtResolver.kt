package com.cadence.music.data.metadata

import com.cadence.music.data.db.TrackEntity
import com.cadence.music.data.source.SubsonicSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves album art URLs for tracks. Server tracks use the Subsonic coverArt
 * endpoint directly; local tracks are matched to MusicBrainz once per
 * (artist, album) and served from Cover Art Archive.
 */
class ArtResolver(private val subsonic: SubsonicSource) {

    private val mbCache = HashMap<String, String?>()
    private val mutex = Mutex()

    suspend fun urlFor(track: TrackEntity): String? {
        if (track.albumName.isBlank()) return null
        if (track.sourceId == "subsonic" && track.albumKey != null) {
            return subsonic.coverArtUrl(track.albumKey)
        }
        val key = "${track.artistName}::${track.albumName}"
        return mutex.withLock { mbCache[key] } ?: run {
            val mbid = MusicBrainz.searchReleaseGroup(track.artistName, track.albumName)
            val url = mbid?.let { MusicBrainz.coverArtUrl(it) }
            mutex.withLock { mbCache[key] = url }
            url
        }
    }
}
