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
import com.cadence.music.data.source.Album
import com.cadence.music.data.source.LocalSource
import com.cadence.music.data.source.PlexSource
import com.cadence.music.data.source.SubsonicSource
import com.cadence.music.data.source.Track
import com.cadence.music.data.tags.albumNormKey
import com.cadence.music.data.tags.primaryArtist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

fun sourcesFor(mode: LibraryMode): Set<String>? = when (mode) {
    LibraryMode.LOCAL_ONLY -> setOf("local")
    LibraryMode.API_ONLY -> setOf("subsonic", "jellyfin", "emby", "plex")
    LibraryMode.HYBRID -> null // null = all sources (future-proof; never enumerate)
}

/** A server track with a downloaded file belongs to the local set (shows "Downloaded" in UI). */
fun isDownloaded(sourceId: String, path: String?): Boolean =
    sourceId != "local" && path?.startsWith("file:") == true

/** Display predicate: downloaded tracks are visible (and shufflable) in local-only mode. */
fun isIncluded(sourceId: String, path: String?, mode: LibraryMode): Boolean = when (mode) {
    LibraryMode.HYBRID -> true
    LibraryMode.API_ONLY -> sourceId != "local"
    LibraryMode.LOCAL_ONLY -> sourceId == "local" || isDownloaded(sourceId, path)
}

/** Strips the "<entryId>:" prefix, recovering the source-level remote key. */
fun remoteKey(serverId: String, entry: ServerEntry): String = serverId.removePrefix("${entry.id}:")

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val doneAlbums: Int, val totalAlbums: Int, val serverUrl: String) : SyncState
    data class Done(val tracksFetched: Int) : SyncState
    data class Failed(val message: String) : SyncState
}

class LibraryRepository(
    private val db: AppDatabase,
    private val local: LocalSource,
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
        val id = entryIdOf(serverId) ?: return null
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

    suspend fun coverArtFor(albumKey: String): String? {
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

    /**
     * Active-entry filter for SQL builders: empty when nothing is disabled
     * (SQL byte-identical to the unfiltered queries), else active ids + "local"
     * so local rows survive the AND.
     */
    private fun activePrefixesFor(list: List<ServerEntry>): Set<String> =
        if (list.any { !it.active }) list.filter { it.active }.map { it.id }.toSet() + "local"
        else emptySet()

    private fun activePrefixesSnapshot(): Set<String> = activePrefixesFor(prefs.servers)

    private fun observeActivePrefixes(): Flow<Set<String>> =
        prefs.observeServers().map { activePrefixesFor(it) }

    private fun observeModeAndActive(): Flow<Pair<LibraryMode, Set<String>>> =
        combine(prefs.observeMode(), observeActivePrefixes()) { mode, active -> mode to active }

    // Full-list read kept ONLY for one-shot shuffle-all (Home + Library FAB).
    // All scrolling UI must use tracksPaged()/searchPaged().
    fun tracks(): Flow<List<TrackEntity>> =
        combine(db.trackDao().observeAll(), prefs.observeMode(), observeActivePrefixes()) { list, mode, active ->
            list.filter { isIncluded(it.sourceId, it.path, mode) && (active.isEmpty() || isEntryActive(it.sourceId, it.serverId, active)) }
        }

    // Paged reads for big libraries; pageSize 50 ≈ 3 screens, maxSize 300 bounds memory,
    // no placeholders (counts shift during sync anyway).
    private val trackPagingConfig = PagingConfig(pageSize = 50, maxSize = 300, enablePlaceholders = false)

    fun tracksPaged(sort: SongSort, ascending: Boolean): Flow<PagingData<TrackEntity>> =
        observeModeAndActive().flatMapLatest { (mode, active) ->
            Pager(trackPagingConfig) {
                db.trackDao().tracksPaged(
                    TrackQueries.tracksQuery(sort, ascending, sourcesFor(mode), mode == LibraryMode.LOCAL_ONLY, active)
                )
            }.flow
        }

    fun searchPaged(query: String): Flow<PagingData<TrackEntity>> =
        observeModeAndActive().flatMapLatest { (mode, active) ->
            Pager(trackPagingConfig) {
                db.trackDao().tracksPaged(
                    TrackQueries.searchQuery(query, sourcesFor(mode), mode == LibraryMode.LOCAL_ONLY, active)
                )
            }.flow
        }
    fun albums(): Flow<List<AlbumEntity>> =
        observeModeAndActive().flatMapLatest { (mode, active) ->
            val base = when (mode) {
                // A downloaded album stays visible via its tracks' files (albums carry no path).
                LibraryMode.LOCAL_ONLY -> db.albumDao().observeAlbumsOffline()
                LibraryMode.API_ONLY -> db.albumDao().observeAlbumsFor(
                    TrackQueries.albumsForQuery(setOf("subsonic", "jellyfin", "emby", "plex"), active)
                )
                LibraryMode.HYBRID -> db.albumDao().observeAll()
            }
            base.map { list ->
                if (active.isEmpty()) list
                else list.filter { isEntryActive(it.sourceId, it.serverId, active) }
            }
        }
    fun artistNames(): Flow<List<String>> =
        observeModeAndActive().flatMapLatest { (mode, active) ->
            val s = sourcesFor(mode)
            if (s == null && active.isEmpty()) db.trackDao().observeArtistNames()
            else db.trackDao().observeArtistNamesFor(
                TrackQueries.artistNamesQuery(s, mode == LibraryMode.LOCAL_ONLY, active)
            )
        }
    /** Mode-aware total used for the Library counter; paging-safe (separate COUNT, not itemCount). */
    fun observeTrackCount(): Flow<Int> =
        observeModeAndActive().flatMapLatest { (mode, active) ->
            val s = sourcesFor(mode)
            if (s == null && active.isEmpty()) db.trackDao().observeCountAll()
            else db.trackDao().observeCountFor(
                TrackQueries.countQuery(s, mode == LibraryMode.LOCAL_ONLY, active)
            )
        }
    fun albumGroups(): Flow<List<com.cadence.music.data.db.AlbumGroup>> =
        observeModeAndActive().flatMapLatest { (mode, active) ->
            val s = sourcesFor(mode)
            if (s == null && active.isEmpty()) db.trackDao().observeAlbumGroups()
            else db.trackDao().observeAlbumGroupsFor(
                TrackQueries.albumGroupsQuery(s, mode == LibraryMode.LOCAL_ONLY, active)
            )
        }

    suspend fun tracksByArtist(name: String): List<TrackEntity> {
        val list = db.trackDao().byArtist(name)
        val mode = prefs.mode
        val active = activePrefixesSnapshot()
        return list.filter { isIncluded(it.sourceId, it.path, mode) && (active.isEmpty() || isEntryActive(it.sourceId, it.serverId, active)) }
    }

    suspend fun tracksByAlbumNorm(norm: String): List<TrackEntity> {
        val list = db.trackDao().byAlbumNorm(norm)
        val mode = prefs.mode
        val active = activePrefixesSnapshot()
        return list.filter { isIncluded(it.sourceId, it.path, mode) && (active.isEmpty() || isEntryActive(it.sourceId, it.serverId, active)) }
    }

    /** Per-entry sync failures (entryId → message); cleared on success. */
    val lastSyncError = MutableStateFlow<Map<String, String>>(emptyMap())

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null
    /** Starts a sync unless one is running (returns the running job). Survives UI navigation. */
    @Synchronized
    fun launchSync(): Job {
        syncJob?.takeIf { it.isActive }?.let { return it }
        return syncScope.launch { runSync() }.also { syncJob = it }
    }

    /**
     * App-scoped sync: same body as [syncAll] plus per-album progress and one
     * retry pass per entry (inside [syncEntry]). One bad server still doesn't
     * abort the rest; the first entry-level message becomes the terminal Failed.
     */
    private suspend fun runSync() {
        try {
            // Local part honors mode as today (LOCAL_ONLY/HYBRID sync local files).
            if (prefs.mode != LibraryMode.API_ONLY) syncLocal()
            var tracksFetched = 0
            var failure: String? = null
            for (entry in prefs.servers.filter { it.active }) {
                // One bad server must not abort the rest (same isolation as syncAll).
                try {
                    tracksFetched += syncEntry(entry) { done, total ->
                        _syncState.value = SyncState.Running(done, total, entry.url)
                    }.tracksFetched
                    if (lastSyncError.value.containsKey(entry.id)) {
                        lastSyncError.value = lastSyncError.value - entry.id
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val msg = e.message ?: "Sync failed"
                    lastSyncError.value = lastSyncError.value + (entry.id to msg)
                    if (failure == null) failure = msg
                }
            }
            pruneUnknownEntries()
            _syncState.value = if (failure != null) SyncState.Failed(failure) else SyncState.Done(tracksFetched)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _syncState.value = SyncState.Failed(e.message ?: "Sync failed")
        }
    }

    suspend fun syncAll() {
        // Local part honors mode as today (LOCAL_ONLY/HYBRID sync local files).
        if (prefs.mode != LibraryMode.API_ONLY) syncLocal()
        for (entry in prefs.servers.filter { it.active }) {
            // One bad server must not abort the rest (same isolation as per-album handling).
            try {
                syncServerEntry(entry)
                if (lastSyncError.value.containsKey(entry.id)) {
                    lastSyncError.value = lastSyncError.value - entry.id
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastSyncError.value = lastSyncError.value + (entry.id to (e.message ?: "Sync failed"))
            }
        }
        pruneUnknownEntries()
    }

    /**
     * Drops rows for entries no longer configured (deleted servers).
     * Unprefixed non-local rows (shouldn't exist post-v9) are left alone.
     */
    private suspend fun pruneUnknownEntries() {
        val known = prefs.servers.map { it.id }.toSet() + "local"
        fun unknown(serverId: String): Boolean {
            val prefix = entryIdOf(serverId) ?: return false
            return prefix != "local" && prefix !in known
        }
        val staleTracks = db.trackDao().observeAll().first().filter { unknown(it.serverId) }
        val staleAlbums = db.albumDao().observeAll().first().filter { unknown(it.serverId) }
        val staleDownloads = db.downloadDao().observeAll().first().filter { unknown(it.trackServerId) }
        if (staleTracks.isEmpty() && staleAlbums.isEmpty() && staleDownloads.isEmpty()) return
        db.withTransaction {
            staleTracks.forEach { t ->
                if (t.sourceId != "local" && t.path?.startsWith("file:") == true) {
                    runCatching { java.io.File(java.net.URI(t.path)).delete() }
                }
                db.downloadDao().delete(t.serverId, t.sourceId)
                db.trackDao().delete(t)
            }
            staleAlbums.forEach { db.albumDao().deleteByServerId(it.serverId) }
            staleDownloads.forEach { db.downloadDao().delete(it.trackServerId, it.sourceId) }
            db.playlistDao().deleteOrphanRows()
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
                artistName = primaryArtist(t.artist),
                albumName = t.album,
                albumNorm = albumNormKey(t.album, t.artist),
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
    suspend fun syncServerEntry(entry: ServerEntry): SyncResult = syncEntry(entry, null)

    /**
     * Shared per-entry sync body. Albums whose track fetch throws are collected
     * and re-attempted ONCE at the end, then given up. Cancellation is never
     * swallowed. Progress (when observed) starts at (0, total) after listAlbums
     * and ticks after every album.
     */
    private suspend fun syncEntry(entry: ServerEntry, onProgress: ((done: Int, total: Int) -> Unit)?): SyncResult {
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
        var done = 0
        onProgress?.invoke(0, remoteAlbums.size)
        val failedKeys = mutableListOf<Album>()
        for (album in remoteAlbums) {
            val nsAlbumKey = namespacedKey(entry.id, album.key)
            val existing = known[nsAlbumKey]
            if (existing != null && existing.remoteCreated == album.remoteCreated) {
                done++
                onProgress?.invoke(done, remoteAlbums.size)
                continue
            }

            // Fetch before writing: one bad album must not abort the whole sync,
            // nor leave an album row with no tracks behind.
            val tracks: List<Track>? = try {
                fetchAlbumTracks(s, album.key)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failedKeys.add(album)
                done++
                onProgress?.invoke(done, remoteAlbums.size)
                continue
            }
            if (tracks == null) {
                done++
                onProgress?.invoke(done, remoteAlbums.size)
                continue
            }
            val r = storeAlbum(entry, sourceId, album, tracks, existing)
            fetchedAlbums += r.albumsFetched
            fetchedTracks += r.tracksFetched
            done++
            onProgress?.invoke(done, remoteAlbums.size)
        }
        // ONE retry pass over failed album keys, then give up.
        for (album in failedKeys) {
            val tracks = try {
                fetchAlbumTracks(s, album.key)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                continue
            } ?: continue
            val r = storeAlbum(entry, sourceId, album, tracks, known[namespacedKey(entry.id, album.key)])
            fetchedAlbums += r.albumsFetched
            fetchedTracks += r.tracksFetched
        }
        pruneDeletedAlbums(entry, known.keys, remoteAlbums.map { namespacedKey(entry.id, it.key) }.toSet())
        return SyncResult(fetchedAlbums, fetchedTracks)
    }

    private suspend fun fetchAlbumTracks(s: Any, albumKey: String): List<Track>? = when (s) {
        is SubsonicSource -> s.albumTracksByKey(albumKey)
        is EmbyLikeSource -> s.albumTracksByKey(albumKey)
        is PlexSource -> s.albumTracksByKey(albumKey)
        else -> null
    }

    private suspend fun storeAlbum(
        entry: ServerEntry,
        sourceId: String,
        album: Album,
        tracks: List<Track>,
        existing: AlbumEntity?,
    ): SyncResult {
        val nsAlbumKey = namespacedKey(entry.id, album.key)
        val existingTracks = db.trackDao().byAlbumKey(nsAlbumKey).associateBy { it.serverId }
        val entities = tracks.map { t ->
            val nsKey = namespacedKey(entry.id, t.key)
            val prev = existingTracks[nsKey]
            TrackEntity(
                id = prev?.id ?: 0,
                sourceId = sourceId,
                serverId = nsKey,
                title = t.title,
                artistName = primaryArtist(t.artist),
                albumName = t.album,
                albumNorm = albumNormKey(t.album, t.artist),
                albumKey = t.albumKey?.let { namespacedKey(entry.id, it) },
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
            val remoteKeys = tracks.map { namespacedKey(entry.id, it.key) }.toSet()
            val removed = existingTracks.values.filter { it.serverId !in remoteKeys }
            removed.forEach { db.trackDao().delete(it) }
            if (removed.isNotEmpty()) db.playlistDao().deleteOrphanRows()
        }
        return SyncResult(if (tracks.isNotEmpty()) 1 else 0, tracks.size)
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
            if (track != null && track.sourceId != "local" && fileUri != null && fileUri.startsWith("file:")) {
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
