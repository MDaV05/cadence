package com.cadence.music.data

import android.content.ContentUris
import android.provider.MediaStore
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.cadence.music.data.db.AlbumEntity
import com.cadence.music.data.db.AppDatabase
import com.cadence.music.data.db.DownloadEntity
import com.cadence.music.data.db.TrackEntity
import com.cadence.music.data.db.TrackQueries
import com.cadence.music.data.prefs.LibraryMode
import com.cadence.music.data.prefs.Prefs
import com.cadence.music.data.prefs.Prefs.SongSort
import com.cadence.music.data.source.LocalSource
import com.cadence.music.data.source.SubsonicSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest

fun sourcesFor(mode: LibraryMode): Set<String>? = when (mode) {
    LibraryMode.LOCAL_ONLY -> setOf("local")
    LibraryMode.API_ONLY -> setOf("subsonic")
    LibraryMode.HYBRID -> null // null = all sources (future-proof; never enumerate)
}

class LibraryRepository(
    private val db: AppDatabase,
    private val local: LocalSource,
    val subsonic: SubsonicSource,
    private val prefs: Prefs,
    private val context: android.content.Context,
) {
    // Full-list read kept ONLY for one-shot shuffle-all (Home + Library FAB).
    // All scrolling UI must use tracksPaged()/searchPaged().
    fun tracks(): Flow<List<TrackEntity>> =
        combine(db.trackDao().observeAll(), prefs.observeMode()) { list, mode ->
            val s = sourcesFor(mode)
            if (s == null) list else list.filter { it.sourceId in s }
        }

    // Paged reads for big libraries; pageSize 50 ≈ 3 screens, maxSize 300 bounds memory,
    // no placeholders (counts shift during sync anyway).
    private val trackPagingConfig = PagingConfig(pageSize = 50, maxSize = 300, enablePlaceholders = false)

    fun tracksPaged(sort: SongSort, ascending: Boolean): Flow<PagingData<TrackEntity>> =
        prefs.observeMode().flatMapLatest { mode ->
            Pager(trackPagingConfig) {
                db.trackDao().tracksPaged(TrackQueries.tracksQuery(sort, ascending, sourcesFor(mode)))
            }.flow
        }

    fun searchPaged(query: String): Flow<PagingData<TrackEntity>> =
        prefs.observeMode().flatMapLatest { mode ->
            Pager(trackPagingConfig) {
                db.trackDao().tracksPaged(TrackQueries.searchQuery(query, sourcesFor(mode)))
            }.flow
        }
    fun albums(): Flow<List<AlbumEntity>> =
        combine(db.albumDao().observeAll(), prefs.observeMode()) { list, mode ->
            val s = sourcesFor(mode)
            if (s == null) list else list.filter { it.sourceId in s }
        }
    fun artistNames(): Flow<List<String>> =
        prefs.observeMode().flatMapLatest { mode ->
            val s = sourcesFor(mode)
            if (s == null) db.trackDao().observeArtistNames() else db.trackDao().observeArtistNamesFor(s)
        }
    /** Mode-aware total used for the Library counter; paging-safe (separate COUNT, not itemCount). */
    fun observeTrackCount(): Flow<Int> =
        prefs.observeMode().flatMapLatest { mode ->
            val s = sourcesFor(mode)
            if (s == null) db.trackDao().observeCountAll() else db.trackDao().observeCountFor(s)
        }
    fun albumGroups(): Flow<List<com.cadence.music.data.db.AlbumGroup>> =
        prefs.observeMode().flatMapLatest { mode ->
            val s = sourcesFor(mode)
            if (s == null) db.trackDao().observeAlbumGroups() else db.trackDao().observeAlbumGroupsFor(s)
        }

    suspend fun tracksByArtist(name: String): List<TrackEntity> {
        val list = db.trackDao().byArtist(name)
        val s = sourcesFor(prefs.mode)
        return if (s == null) list else list.filter { it.sourceId in s }
    }

    suspend fun tracksByAlbum(name: String): List<TrackEntity> {
        val list = db.trackDao().byAlbum(name)
        val s = sourcesFor(prefs.mode)
        return if (s == null) list else list.filter { it.sourceId in s }
    }

    suspend fun syncAll() {
        when (prefs.mode) {
            LibraryMode.LOCAL_ONLY -> syncLocal()
            LibraryMode.API_ONLY -> syncServer()
            LibraryMode.HYBRID -> {
                syncLocal()
                syncServer()
            }
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
        // Remove rows for files that vanished from MediaStore — atomically,
        // so a crash between insert and orphan-cleanup can't leave orphans.
        val scannedKeys = entities.map { it.serverId }.toSet()
        val removed = known.values.filter { it.sourceId == "local" && it.serverId !in scannedKeys }
        db.withTransaction {
            db.trackDao().insertAll(entities)
            removed.forEach { db.trackDao().delete(it) }
            if (removed.isNotEmpty()) db.playlistDao().deleteOrphanRows()
        }
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

            // Fetch before writing: one bad album must not abort the whole sync,
            // nor leave an album row with no tracks behind.
            val tracks = runCatching { subsonic.albumTracksByKey(album.key) }.getOrNull() ?: continue
            if (tracks.isNotEmpty()) {
                fetchedAlbums++
                fetchedTracks += tracks.size
            }

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
            db.withTransaction {
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
                if (entities.isNotEmpty()) db.trackDao().insertAll(entities)
                val remoteKeys = tracks.map { it.key }.toSet()
                val removed = existingTracks.values.filter { it.serverId !in remoteKeys }
                removed.forEach { db.trackDao().delete(it) }
                if (removed.isNotEmpty()) db.playlistDao().deleteOrphanRows()
            }
        }
        pruneDeletedAlbums(known.keys, remoteAlbums.map { it.key }.toSet())
        return SyncResult(fetchedAlbums, fetchedTracks)
    }

    /** Drops albums (and their tracks, files, download rows) deleted server-side. */
    private suspend fun pruneDeletedAlbums(knownKeys: Set<String>, remoteKeys: Set<String>) {
        val gone = knownKeys - remoteKeys
        if (gone.isEmpty()) return
        val staleTracks = gone.flatMap { db.trackDao().byAlbumKey(it) }
        db.withTransaction {
            staleTracks.forEach { t ->
                if (t.sourceId == "subsonic" && t.path?.startsWith("file:") == true) {
                    runCatching { java.io.File(java.net.URI(t.path)).delete() }
                }
                db.downloadDao().delete(t.serverId, t.sourceId)
                db.trackDao().delete(t)
            }
            gone.forEach { db.albumDao().deleteByServerId(it) }
            db.playlistDao().deleteOrphanRows()
        }
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

    suspend fun setPlaylistCover(id: Long, path: String?) =
        db.playlistDao().setCoverPath(id, path)

    /** First track of the playlist — its album art is the default cover. */
    suspend fun playlistFirstTrack(id: Long) = db.playlistDao().firstTrackFor(id)

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
