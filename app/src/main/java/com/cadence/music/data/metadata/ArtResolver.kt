package com.cadence.music.data.metadata

import com.cadence.music.data.LibraryRepository
import com.cadence.music.data.db.TrackEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves album art URLs for tracks. Server tracks route through the library
 * (per-entry cover art); local tracks are matched to MusicBrainz once per
 * (artist, album) and served from Cover Art Archive.
 */
class ArtResolver(private val library: LibraryRepository) {

    private val mbCache = HashMap<String, String?>()
    private val mutex = Mutex()

    suspend fun urlFor(track: TrackEntity): String? {
        // User override first: exact track cover, then album cover. File paths
        // go out as file:// so Coil's FileUriFetcher picks them up.
        library.artOverrideFor(track)?.let { return "file://$it" }
        if (track.albumName.isBlank()) return null
        // Local tracks: MediaStore's album art provider, instant and offline.
        if (track.sourceId == "local") {
            return track.albumMediaId?.let {
                android.content.ContentUris.withAppendedId(
                    android.net.Uri.parse("content://media/external/audio/albumart"), it,
                ).toString()
            }
        }
        if (track.sourceId != "local" && track.albumKey != null) {
            return library.coverArtFor(track.albumKey)
        }
        val key = "${track.artistName}::${track.albumName}"
        return mutex.withLock { mbCache[key] } ?: run {
            val url = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                MusicBrainz.searchReleaseGroup(track.artistName, track.albumName)
                    ?.let { MusicBrainz.coverArtUrl(it) }
            }
            mutex.withLock { mbCache[key] = url }
            url
        }
    }
}
