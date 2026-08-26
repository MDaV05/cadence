package com.cadence.music.data

import android.content.ContentUris
import android.provider.MediaStore
import com.cadence.music.data.db.AlbumEntity
import com.cadence.music.data.db.AppDatabase
import com.cadence.music.data.db.DownloadEntity
import com.cadence.music.data.db.TrackEntity
import com.cadence.music.data.prefs.LibraryMode
import com.cadence.music.data.prefs.Prefs
import com.cadence.music.data.source.LocalSource
import com.cadence.music.data.source.SubsonicSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow

class LibraryRepository(
    private val db: AppDatabase,
    private val local: LocalSource,
    val subsonic: SubsonicSource,
    private val prefs: Prefs,
    private val context: android.content.Context,
) {
    fun tracks(): Flow<List<TrackEntity>> = db.trackDao().observeAll()
    fun albums(): Flow<List<AlbumEntity>> = db.albumDao().observeAll()
    fun artistNames(): Flow<List<String>> = db.trackDao().observeArtistNames()
    fun albumGroups(): Flow<List<com.cadence.music.data.db.AlbumGroup>> =
        db.trackDao().observeAlbumGroups()

    suspend fun tracksByArtist(name: String): List<TrackEntity> =
        db.trackDao().byArtist(name)

    suspend fun tracksByAlbum(name: String): List<TrackEntity> =
        db.trackDao().byAlbum(name)

    suspend fun syncAll() {
        when (prefs.mode) {
            LibraryMode.LOCAL_ONLY -> syncLocal()
            LibraryMode.API_ONLY -> syncServer()
            LibraryMode.HYBRID -> { syncLocal(); syncServer() }
        }
    }

    suspend fun syncLocal() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // Preserve previously-read values (tag parsing is file I/O; only parse once per track).
        val known = db.trackDao().observeAll().first().associateBy { it.serverId }

        // Upsert with the existing row id so play stats and playlist
        // references survive; REPLACE with a fresh id would orphan both.
        val entities = local.scan().map { t ->
            val mediaId = t.key.removePrefix("local:").toLong()
            val uri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId
            )
            val prev = known[t.key]
            val replayGainDb = prev?.replayGainDb
                ?: com.cadence.music.data.tags.ReplayGainReader.read(context, uri)
            TrackEntity(
                id = prev?.id ?: 0,
                sourceId = "local",
                serverId = t.key,
                title = t.title,
                artistName = t.artist,
                albumName = t.album,
                path = uri.toString(),
                durationMs = t.durationMs,
                trackNumber = 0,
                replayGainDb = replayGainDb,
                albumMediaId = t.albumMediaId,
                playCount = prev?.playCount ?: 0,
                lastPlayed = prev?.lastPlayed,
                starred = prev?.starred ?: false,
            )
        }
        db.trackDao().insertAll(entities)
        // Remove rows for files that vanished from MediaStore (and any playlist
        // entries that pointed at them).
        val scannedKeys = entities.map { it.serverId }.toSet()
        val removed = known.values.filter { it.sourceId == "local" && it.serverId !in scannedKeys }
        removed.forEach { db.trackDao().delete(it) }
        if (removed.isNotEmpty()) db.playlistDao().deleteOrphanRows()
    }

    /**
     * Incremental: lists albums (cheap, 1 request per 500) and only re-fetches
     * tracks for albums that are new or whose server-side `created` changed.
     */
    suspend fun syncServer(): SyncResult {
        if (prefs.server == null) return SyncResult(0, 0)
        val remoteAlbums = subsonic.listAlbums()
        val known = db.albumDao().bySource("subsonic").associateBy { it.serverId }

        var fetchedAlbums = 0
        var fetchedTracks = 0
        for (album in remoteAlbums) {
            val existing = known[album.key]
            if (existing != null && existing.remoteCreated == album.remoteCreated) continue

            db.albumDao().upsert(
                AlbumEntity(
                    id = existing?.id ?: 0,
                    sourceId = "subsonic",
                    serverId = album.key,
                    title = album.title,
                    artistName = album.artist,
                    year = album.year,
                    remoteCreated = album.remoteCreated,
                )
            )
            val tracks = subsonic.albumTracksByKey(album.key)
            if (tracks.isEmpty()) continue
            fetchedAlbums++
            fetchedTracks += tracks.size

            // Upsert with the existing row id so play stats and playlist
            // references survive re-syncs.
            val existingTracks = db.trackDao().byAlbumKey(album.key).associateBy { it.serverId }
            val entities = tracks.map { t ->
                val prev = existingTracks[t.key]
                TrackEntity(
                    id = prev?.id ?: 0,
                    sourceId = "subsonic",
                    serverId = t.key,
                    title = t.title,
                    artistName = t.artist,
                    albumName = t.album,
                    albumKey = t.albumKey,
                    path = null,
                    durationMs = t.durationMs,
                    trackNumber = 0,
                    replayGainDb = prev?.replayGainDb,
                    albumMediaId = prev?.albumMediaId,
                    playCount = prev?.playCount ?: 0,
                    lastPlayed = prev?.lastPlayed,
                    // OR-preserve: a star made offline survives until the server
                    // confirms the unstar on a later sync.
                    starred = t.starred || (prev?.starred ?: false),
                )
            }
            db.trackDao().insertAll(entities)
            val remoteKeys = tracks.map { it.key }.toSet()
            val removed = existingTracks.values.filter { it.serverId !in remoteKeys }
            removed.forEach { db.trackDao().delete(it) }
            if (removed.isNotEmpty()) db.playlistDao().deleteOrphanRows()
        }
        return SyncResult(fetchedAlbums, fetchedTracks)
    }

    // ---- Playlists ----

    fun playlists(): Flow<List<com.cadence.music.data.db.PlaylistWithCount>> =
        db.playlistDao().observeAll()

    suspend fun playlist(id: Long) = db.playlistDao().byId(id)

    suspend fun playlistTracks(id: Long): List<TrackEntity> = db.playlistDao().tracksFor(id)

    suspend fun playlistTracksWithRows(id: Long): List<com.cadence.music.data.db.PlaylistTrackRow> {
        val rows = db.playlistDao().rowsFor(id)
        val tracks = db.playlistDao().tracksFor(id).associateBy { it.id }
        return rows.mapNotNull { r -> tracks[r.trackId]?.let { com.cadence.music.data.db.PlaylistTrackRow(r, it) } }
    }

    suspend fun createPlaylist(name: String): Long =
        db.playlistDao().insertPlaylist(com.cadence.music.data.db.PlaylistEntity(name = name))

    suspend fun renamePlaylist(id: Long, name: String) =
        db.playlistDao().renamePlaylist(id, name)

    suspend fun deletePlaylist(id: Long) {
        db.playlistDao().deletePlaylistCascade(id)
    }

    suspend fun addToPlaylist(playlistId: Long, trackId: Long) {
        val next = db.playlistDao().maxPosition(playlistId) + 1
        db.playlistDao().insertTrack(
            com.cadence.music.data.db.PlaylistTrackEntity(
                playlistId = playlistId, trackId = trackId, position = next,
            )
        )
    }

    suspend fun removeFromPlaylist(rowId: Long) = db.playlistDao().removeTrack(rowId)

    // ---- Downloads ----

    /** Download rows joined with their track for display; live-updates as states change. */
    fun observeDownloads(): Flow<List<DownloadStatusRow>> =
        kotlinx.coroutines.flow.combine(db.downloadDao().observeAll(), db.trackDao().observeAll()) { dls, tracks ->
            val byKey = tracks.associateBy { "${it.sourceId}:${it.serverId}" }
            dls.map { DownloadStatusRow(it, byKey["${it.sourceId}:${it.trackServerId}"]) }
        }

    /** Queues a track for download; only server tracks are downloadable. */
    fun enqueueDownload(track: TrackEntity) {
        if (track.sourceId != "subsonic") return
        com.cadence.music.data.downloads.DownloadWorker.enqueue(context, track.id)
    }

    fun enqueueDownloads(tracks: List<TrackEntity>) {
        tracks.filter { it.sourceId == "subsonic" }.forEach { enqueueDownload(it) }
    }

    suspend fun retryDownload(download: DownloadEntity, track: TrackEntity?) {
        if (track != null) {
            enqueueDownload(track)
        } else {
            db.downloadDao().updateProgress(
                download.trackServerId, download.sourceId, "failed", 0,
            )
        }
    }

    /** Removes the downloaded file and its status row; the track stays streamable. */
    suspend fun deleteDownload(download: DownloadEntity, track: TrackEntity?) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val fileUri = track?.path
            if (track != null && track.sourceId == "subsonic" && fileUri != null && fileUri.startsWith("file:")) {
                runCatching {
                    java.io.File(java.net.URI(fileUri)).delete()
                    db.trackDao().setPath(track.id, null)
                }
            }
            db.downloadDao().delete(download.trackServerId, download.sourceId)
        }
    }

    /** Flips star locally, then syncs the server (fire-and-forget on failure). */
    suspend fun toggleStar(track: TrackEntity) {
        val starred = !track.starred
        db.trackDao().setStarred(track.id, starred)
        if (track.sourceId == "subsonic") {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { subsonic.setStarred(track.serverId.removePrefix("sub:"), starred) }
            }
        }
    }
}

data class DownloadStatusRow(val download: DownloadEntity, val track: TrackEntity?)

data class SyncResult(val albumsFetched: Int, val tracksFetched: Int)
