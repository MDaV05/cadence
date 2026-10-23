package com.cadence.music.data.metadata

import android.content.ContentUris
import android.net.Uri
import com.cadence.music.data.LibraryRepository
import com.cadence.music.data.db.AppDatabase
import com.cadence.music.data.db.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Resolves album art URLs for tracks. Server tracks route through the library
 * (per-entry cover art); local tracks are matched to MusicBrainz once per
 * (artist, album) and served from Cover Art Archive.
 */
class ArtResolver(
    private val library: LibraryRepository,
    private val db: AppDatabase? = null,
) {

    private val mbCache = HashMap<String, String?>()
    private val mutex = Mutex()

    /**
     * Reached from produceState/LaunchedEffect composition scopes: any surprise
     * (hostile MusicBrainz JSON, MediaStore uri issues) must degrade to "no art",
     * never crash the UI.
     */
    suspend fun urlFor(track: TrackEntity): String? =
        runCatching { resolveUrlFor(track) }.getOrNull()

    private suspend fun resolveUrlFor(track: TrackEntity): String? {
        // 1. User override check: exact track cover, then album cover. File paths
        // go out as file:// so Coil's FileUriFetcher picks them up.
        library.artOverrideFor(track)?.let { return "file://$it" }

        // 2. MediaStore album art check: instant and offline for local tracks.
        if (track.sourceId == "local") {
            track.albumMediaId?.let {
                return ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"), it,
                ).toString()
            }
        }

        // 3. Server-provided track art / album art
        if (track.sourceId != "local") {
            library.trackCoverArtFor(track.serverId)?.let { return it }
        }
        val albumKey = track.albumKey
        if (track.sourceId != "local" && albumKey != null) {
            library.coverArtFor(albumKey)?.let { return it }
        }

        // 4. Room database persistent check: check if track.albumKey exists and has album.artPath.
        // If so, return it immediately.
        if (albumKey != null && db != null) {
            val cachedAlbum = withContext(Dispatchers.IO) {
                db.albumDao().byServerId(albumKey)
            }
            if (!cachedAlbum?.artPath.isNullOrBlank()) {
                return cachedAlbum?.artPath
            }
        }

        // 6. Keep in-memory cache as secondary fast lookup
        if (track.artistName.isBlank() || track.albumName.isBlank()) return null
        val key = "${track.artistName}::${track.albumName}"
        return mutex.withLock {
            if (mbCache.containsKey(key)) {
                return@withLock mbCache[key]
            }

            // 5. Network lookup via MusicBrainz Cover Art Archive, and if found, persist to Room
            val (url, mbid) = withContext(Dispatchers.IO) {
                val releaseGroupMbid = MusicBrainz.searchReleaseGroup(track.artistName, track.albumName)
                val artUrl = releaseGroupMbid?.let { MusicBrainz.coverArtUrl(it) }
                artUrl to releaseGroupMbid
            }

            if (url != null && albumKey != null && db != null) {
                withContext(Dispatchers.IO) {
                    db.albumDao().updateArt(albumKey, url, mbid)
                }
            }

            mbCache[key] = url
            url
        }
    }
}
