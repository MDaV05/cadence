package com.cadence.music.data

import android.content.ContentUris
import android.provider.MediaStore
import com.cadence.music.data.db.AlbumEntity
import com.cadence.music.data.db.AppDatabase
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

        val entities = local.scan().map { t ->
            val mediaId = t.key.removePrefix("local:").toLong()
            val uri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId
            )
            val replayGainDb = known[t.key]?.replayGainDb
                ?: com.cadence.music.data.tags.ReplayGainReader.read(context, uri)
            TrackEntity(
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
            )
        }
        db.trackDao().insertAll(entities)
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

            db.trackDao().deleteByAlbumKey(album.key)
            val entities = tracks.map { t ->
                TrackEntity(
                    sourceId = "subsonic",
                    serverId = t.key,
                    title = t.title,
                    artistName = t.artist,
                    albumName = t.album,
                    albumKey = t.albumKey,
                    path = null,
                    durationMs = t.durationMs,
                    trackNumber = 0,
                )
            }
            db.trackDao().insertAll(entities)
        }
        return SyncResult(fetchedAlbums, fetchedTracks)
    }

    // ---- Playlists ----

    fun playlists(): Flow<List<com.cadence.music.data.db.PlaylistWithCount>> =
        db.playlistDao().observeAll()

    suspend fun playlist(id: Long) = db.playlistDao().byId(id)

    suspend fun playlistTracks(id: Long): List<TrackEntity> = db.playlistDao().tracksFor(id)

    suspend fun createPlaylist(name: String): Long =
        db.playlistDao().insertPlaylist(com.cadence.music.data.db.PlaylistEntity(name = name))

    suspend fun deletePlaylist(id: Long) {
        db.playlistDao().deleteTracksFor(id)
        db.playlistDao().deletePlaylist(id)
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
}

data class SyncResult(val albumsFetched: Int, val tracksFetched: Int)
