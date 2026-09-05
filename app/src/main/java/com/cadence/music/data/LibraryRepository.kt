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
import com.cadence.music.data.prefs.ServerConfig
import com.cadence.music.data.prefs.ServerEntry
import com.cadence.music.data.prefs.ServerType
import com.cadence.music.data.source.EmbyLikeSource
import com.cadence.music.data.source.EmbySource
import com.cadence.music.data.source.JellyfinSource
import com.cadence.music.data.source.LocalSource
import com.cadence.music.data.source.PlexSource
import com.cadence.music.data.source.SubsonicSource
import com.cadence.music.data.source.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest

fun sourcesFor(mode: LibraryMode): Set<String>? = when (mode) {
    LibraryMode.LOCAL_ONLY -> setOf("local")
    LibraryMode.API_ONLY -> setOf("subsonic")
    LibraryMode.HYBRID -> null // null = all sources (future-proof; never enumerate)
}

/** A server track with a downloaded file belongs to the local set (shows "Downloaded" in UI). */
fun isDownloaded(sourceId: String, path: String?): Boolean =
    sourceId == "subsonic" && path?.startsWith("file:") == true

/** Display predicate: downloaded tracks are visible (and shufflable) in local-only mode. */
fun isIncluded(sourceId: String, path: String?, mode: LibraryMode): Boolean = when (mode) {
    LibraryMode.HYBRID -> true
    LibraryMode.API_ONLY -> sourceId == "subsonic"
    LibraryMode.LOCAL_ONLY -> sourceId == "local" || isDownloaded(sourceId, path)
}

/** Strips the "<entryId>:" prefix, recovering the source-level remote key. */
fun remoteKey(serverId: String, entry: ServerEntry): String = serverId.removePrefix("${entry.id}:")

class LibraryRepository(
    private val db: AppDatabase,
    private val local: LocalSource,
    // Retained for the Task 5 ServerTab rewrite (library.subsonic.ping()); Task 5 deletes.
    val subsonic: SubsonicSource,
    private val prefs: Prefs,
    private val context: android.content.Context,
) {
    private val deviceId: String by lazy {
        android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "cadence"
    }

    private fun sourceFor(entry: ServerEntry): Any = when (entry.type) {
        ServerType.SUBSONIC -> SubsonicSource { ServerConfig(entry.url, entry.user, entry.password ?: "") }
        ServerType.JELLYFIN -> JellyfinSource(entry, deviceId)
        ServerType.EMBY -> EmbySource(entry, deviceId)
        ServerType.PLEX -> PlexSource(entry, deviceId)
    }

    /** Entry id is the segment before the FIRST ':'; null when absent or unknown. */
    fun entryForServerId(serverId: String): ServerEntry? {
        val id = serverId.substringBefore(':').takeIf { serverId.contains(':') } ?: return null
        return prefs.entry(id)
    }

    /** Fresh authenticated stream URL for any namespaced in-memory Track. */
    suspend fun streamUrlFor(track: Track): String? {
        track.localPath?.let { return it }
        val entry = entryForServerId(track.key) ?: return null
        val remote = remoteKey(track.key, entry)
        return when (val s = sourceFor(entry)) {
            is SubsonicSource -> s.streamUrl(track.copy(key = remote))
            is EmbyLikeSource -> s.streamUrl(track.copy(key = remote))
            is PlexSource -> s.streamUrl(track.copy(key = remote))
            else -> null
        }
    }

    fun downloadUrlFor(serverId: String, format: String, bitrate: Int): String? {
        val entry = entryForServerId(serverId) ?: return null
        val remote = remoteKey(serverId, entry)
        return when (val s = sourceFor(entry)) {
            is SubsonicSource -> s.downloadUrl(remote.removePrefix("sub:"), format, bitrate)
            is EmbyLikeSource -> s.downloadUrl(remote)
            is PlexSource -> s.downloadUrl(remote)
            else -> null
        }
    }

    fun coverArtFor(albumKey: String): String? {
        val entry = entryForServerId(albumKey) ?: return null
        val remote = remoteKey(albumKey, entry)
        return when (val s = sourceFor(entry)) {
            is SubsonicSource -> s.coverArtUrl(remote)
            is EmbyLikeSource -> s.coverArtUrl(remote)
            is PlexSource -> s.coverArtUrl(remote)
            else -> null
        }
    }

    suspend fun setStarredFor(serverId: String, starred: Boolean) {
        val entry = entryForServerId(serverId) ?: return
        val remote = remoteKey(serverId, entry)
        when (val s = sourceFor(entry)) {
            is SubsonicSource -> runCatching { s.setStarred(remote.removePrefix("sub:"), starred) }
            is EmbyLikeSource -> runCatching { s.setStarred(remote, starred) }
            // Plex: starring unsupported v1 — silent no-op.
        }
    }

    suspend fun pingEntry(entry: ServerEntry): Boolean = when (val s = sourceFor(entry)) {
        is SubsonicSource -> s.ping()
        is EmbyLikeSource -> s.ping()
        is PlexSource -> s.ping()
        else -> false
    }
    // Full-list read kept ONLY for one-shot shuffle-all (Home + Library FAB).
    // All scrolling UI must use tracksPaged()/searchPaged().
    fun tracks(): Flow<List<TrackEntity>> =
        combine(db.trackDao().observeAll(), prefs.observeMode()) { list, mode ->
            list.filter { isIncluded(it.sourceId, it.path, mode) }
        }

    // Paged reads for big libraries; pageSize 50 ≈ 3 screens, maxSize 300 bounds memory,
    // no placeholders (counts shift during sync anyway).
    private val trackPagingConfig = PagingConfig(pageSize = 50, maxSize = 300, enablePlaceholders = false)

    fun tracksPaged(sort: SongSort, ascending: Boolean): Flow<PagingData<TrackEntity>> =
        prefs.observeMode().flatMapLatest { mode ->
            Pager(trackPagingConfig) {
                db.trackDao().tracksPaged(
                    TrackQueries.tracksQuery(sort, ascending, sourcesFor(mode), mode == LibraryMode.LOCAL_ONLY)
                )
            }.flow
        }

    fun searchPaged(query: String): Flow<PagingData<TrackEntity>> =
        prefs.observeMode().flatMapLatest { mode ->
            Pager(trackPagingConfig) {
                db.trackDao().tracksPaged(
                    TrackQueries.searchQuery(query, sourcesFor(mode), mode == LibraryMode.LOCAL_ONLY)
                )
            }.flow
        }
    fun albums(): Flow<List<AlbumEntity>> =
        prefs.observeMode().flatMapLatest { mode ->
            when (mode) {
                // A downloaded album stays visible via its tracks' files (albums carry no path).
                LibraryMode.LOCAL_ONLY -> db.albumDao().observeAlbumsOffline()
                LibraryMode.API_ONLY -> db.albumDao().observeAlbumsFor(setOf("subsonic"))
                LibraryMode.HYBRID -> db.albumDao().observeAll()
            }
        }
    fun artistNames(): Flow<List<String>> =
        prefs.observeMode().flatMapLatest { mode ->
            val s = sourcesFor(mode)
            if (s == null) db.trackDao().observeArtistNames()
            else db.trackDao().observeArtistNamesFor(s, mode == LibraryMode.LOCAL_ONLY)
        }
    /** Mode-aware total used for the Library counter; paging-safe (separate COUNT, not itemCount). */
    fun observeTrackCount(): Flow<Int> =
        prefs.observeMode().flatMapLatest { mode ->
            val s = sourcesFor(mode)
            if (s == null) db.trackDao().observeCountAll()
            else db.trackDao().observeCountFor(s, mode == LibraryMode.LOCAL_ONLY)
        }
    fun albumGroups(): Flow<List<com.cadence.music.data.db.AlbumGroup>> =
        prefs.observeMode().flatMapLatest { mode ->
            val s = sourcesFor(mode)
            if (s == null) db.trackDao().observeAlbumGroups()
            else db.trackDao().observeAlbumGroupsFor(s, mode == LibraryMode.LOCAL_ONLY)
        }

    suspend fun tracksByArtist(name: String): List<TrackEntity> {
        val list = db.trackDao().byArtist(name)
        val mode = prefs.mode
        return list.filter { isIncluded(it.sourceId, it.path, mode) }
    }

    suspend fun tracksByAlbum(name: String): List<TrackEntity> {
        val list = db.trackDao().byAlbum(name)
        val mode = prefs.mode
        return list.filter { isIncluded(it.sourceId, it.path, mode) }
    }

    suspend fun syncAll() {
        // Local part honors mode as today (LOCAL_ONLY/HYBRID sync local files).
        if (prefs.mode != LibraryMode.API_ONLY) syncLocal()
        for (entry in prefs.servers.filter { it.active }) {
            // One bad server must not abort the rest (same isolation as per-album handling).
            runCatching { syncServerEntry(entry) }.getOrDefault(SyncResult(0, 0))
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

    /** Deprecated: Task 5 rewrites callers (ServerTab save&sync); delete there. */
    @Deprecated("Task 5 rewrites callers")
    suspend fun syncServer(): SyncResult {
        val first = prefs.servers.firstOrNull { it.active } ?: return SyncResult(0, 0)
        return syncServerEntry(first)
    }

    /**
     * Incremental: lists albums (cheap, 1 request per 500) and only re-fetches
     * tracks for albums that are new or whose server-side `created` changed.
     */
    suspend fun syncServerEntry(entry: ServerEntry): SyncResult {
        if (prefs.servers.none { it.active }) return SyncResult(0, 0)
        val sourceId = when (entry.type) {
            ServerType.SUBSONIC -> "subsonic"
            ServerType.JELLYFIN -> "jellyfin"
            ServerType.EMBY -> "emby"
            ServerType.PLEX -> "plex"
        }
        val s = sourceFor(entry)
        val remoteAlbums = when (s) {
            is SubsonicSource -> s.listAlbums()
            is EmbyLikeSource -> s.listAlbums()
            is PlexSource -> s.listAlbums()
            else -> emptyList()
        }
        val known = db.albumDao().bySource(sourceId)
            .filter { it.serverId.startsWith("${entry.id}:") }
            .associateBy { it.serverId }

        var fetchedAlbums = 0
        var fetchedTracks = 0
        for (album in remoteAlbums) {
            val nsAlbumKey = "${entry.id}:${album.key}"
            val existing = known[nsAlbumKey]
            if (existing != null && existing.remoteCreated == album.remoteCreated) continue

            // Fetch before writing: one bad album must not abort the whole sync,
            // nor leave an album row with no tracks behind.
            val tracks = runCatching {
                when (s) {
                    is SubsonicSource -> s.albumTracksByKey(album.key)
                    is EmbyLikeSource -> s.albumTracksByKey(album.key)
                    is PlexSource -> s.albumTracksByKey(album.key)
                    else -> null
                }
            }.getOrNull() ?: continue
            if (tracks.isNotEmpty()) {
                fetchedAlbums++
                fetchedTracks += tracks.size
            }

            val existingTracks = db.trackDao().byAlbumKey(nsAlbumKey).associateBy { it.serverId }
            val entities = tracks.map { t ->
                val nsKey = "${entry.id}:${t.key}"
                val prev = existingTracks[nsKey]
                TrackEntity(
                    id = prev?.id ?: 0,
                    sourceId = sourceId,
                    serverId = nsKey,
                    title = t.title,
                    artistName = t.artist,
                    albumName = t.album,
                    albumKey = t.albumKey?.let { "${entry.id}:$it" },
                    // Preserve the downloaded file: REPLACE would otherwise wipe it on re-sync.
                    path = prev?.path,
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
                        sourceId = sourceId,
                        serverId = nsAlbumKey,
                        title = album.title,
                        artistName = album.artist,
                        year = album.year,
                        remoteCreated = album.remoteCreated,
                    )
                )
                if (entities.isNotEmpty()) db.trackDao().insertAll(entities)
                val remoteKeys = tracks.map { "${entry.id}:${it.key}" }.toSet()
                val removed = existingTracks.values.filter { it.serverId !in remoteKeys }
                removed.forEach { db.trackDao().delete(it) }
                if (removed.isNotEmpty()) db.playlistDao().deleteOrphanRows()
            }
        }
        pruneDeletedAlbums(entry, known.keys, remoteAlbums.map { "${entry.id}:${it.key}" }.toSet())
        return SyncResult(fetchedAlbums, fetchedTracks)
    }

    /** Drops albums (and their tracks, files, download rows) deleted server-side. */
    private suspend fun pruneDeletedAlbums(entry: ServerEntry, knownKeys: Set<String>, remoteKeys: Set<String>) {
        val gone = knownKeys - remoteKeys
        if (gone.isEmpty()) return
        val staleTracks = gone.flatMap { db.trackDao().byAlbumKey(it) }
        db.withTransaction {
            staleTracks.forEach { t ->
                if (t.sourceId != "local" && t.path?.startsWith("file:") == true) {
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
        if (track.sourceId == "local") return
        com.cadence.music.data.downloads.DownloadWorker.enqueue(context, track.id)
    }

    fun enqueueDownloads(tracks: List<TrackEntity>) {
        tracks.filter { it.sourceId != "local" }.forEach { enqueueDownload(it) }
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
        if (track.sourceId != "local") {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                setStarredFor(track.serverId, starred)
            }
        }
    }
}

data class DownloadStatusRow(val download: DownloadEntity, val track: TrackEntity?)

data class SyncResult(val albumsFetched: Int, val tracksFetched: Int)
