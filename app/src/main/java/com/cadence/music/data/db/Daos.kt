package com.cadence.music.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY title")
    fun observeAll(): Flow<List<TrackEntity>>

    @RawQuery(observedEntities = [TrackEntity::class])
    fun tracksPaged(query: SupportSQLiteQuery): PagingSource<Int, TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE serverId = :serverId LIMIT 1")
    suspend fun byServerId(serverId: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE albumKey = :albumKey")
    suspend fun byAlbumKey(albumKey: String): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Query("SELECT COUNT(DISTINCT artistName) FROM tracks WHERE artistName != ''")
    suspend fun artistCount(): Int

    @Query("SELECT COUNT(*) FROM tracks")
    fun observeCountAll(): Flow<Int>

    @RawQuery(observedEntities = [TrackEntity::class])
    fun observeCountFor(query: SupportSQLiteQuery): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Delete
    suspend fun delete(track: TrackEntity)

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayed = :now WHERE id = :id")
    suspend fun recordPlay(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE tracks SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

    @Query("UPDATE tracks SET path = :path WHERE id = :id")
    suspend fun setPath(id: Long, path: String?)

    @Query("SELECT DISTINCT artistName FROM tracks WHERE artistName != '' ORDER BY artistName")
    fun observeArtistNames(): Flow<List<String>>

    @RawQuery(observedEntities = [TrackEntity::class])
    fun observeArtistNamesFor(query: SupportSQLiteQuery): Flow<List<String>>

    @Query("SELECT * FROM tracks WHERE artistName = :name ORDER BY albumName, trackNumber")
    suspend fun byArtist(name: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE albumNorm = :norm ORDER BY trackNumber")
    suspend fun byAlbumNorm(norm: String): List<TrackEntity>

    @Query(
        "SELECT MIN(albumName) AS name, MIN(artistName) AS artistName, albumNorm AS norm, COUNT(*) AS trackCount " +
        "FROM tracks WHERE albumName != '' GROUP BY albumNorm ORDER BY name COLLATE NOCASE"
    )
    fun observeAlbumGroups(): Flow<List<AlbumGroup>>

    @RawQuery(observedEntities = [TrackEntity::class])
    fun observeAlbumGroupsFor(query: SupportSQLiteQuery): Flow<List<AlbumGroup>>

    @Query("SELECT * FROM tracks WHERE playCount > 0 ORDER BY playCount DESC, lastPlayed DESC LIMIT 10")
    suspend fun mostPlayed(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE lastPlayed IS NOT NULL ORDER BY lastPlayed DESC LIMIT 10")
    suspend fun recentlyPlayed(): List<TrackEntity>

    @Query("SELECT * FROM tracks ORDER BY id DESC LIMIT 10")
    suspend fun recentlyAdded(): List<TrackEntity>
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE trackId = :trackId")
    suspend fun byTrackId(trackId: Long): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(lyrics: LyricsEntity)

    @Query("SELECT COUNT(*) FROM lyrics")
    suspend fun count(): Int

    /** Track row ids that have never been checked for lyrics. */
    @Query(
        "SELECT t.id FROM tracks t LEFT JOIN lyrics l ON l.trackId = t.id " +
            "WHERE l.trackId IS NULL"
    )
    suspend fun trackIdsMissingLyrics(): List<Long>
}

@Dao
interface ArtistInfoDao {
    @Query("SELECT * FROM artist_info WHERE name = :name")
    suspend fun byName(name: String): ArtistInfoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(info: ArtistInfoEntity)

    @Query("SELECT COUNT(*) FROM artist_info")
    suspend fun count(): Int

    /** Artists appearing in the library with no cached info row yet. */
    @Query(
        "SELECT DISTINCT artistName FROM tracks WHERE artistName != '' " +
            "AND artistName NOT IN (SELECT name FROM artist_info)"
    )
    suspend fun missingArtistNames(): List<String>

    /** Cached rows old enough to be worth refreshing. */
    @Query("SELECT name FROM artist_info WHERE fetchedAt < :staleBefore")
    suspend fun staleArtistNames(staleBefore: Long): List<String>
}

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE trackServerId = :serverId AND sourceId = :sourceId")
    suspend fun byTrack(sourceId: String, serverId: String): DownloadEntity?

    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query(
        "UPDATE downloads SET status = :status, bytesDone = :bytesDone, " +
            "updatedAt = :now WHERE trackServerId = :serverId AND sourceId = :sourceId"
    )
    suspend fun updateProgress(
        serverId: String,
        sourceId: String,
        status: String,
        bytesDone: Long,
        now: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM downloads WHERE trackServerId = :serverId AND sourceId = :sourceId")
    suspend fun delete(serverId: String, sourceId: String)
}

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums ORDER BY year DESC, title")
    fun observeAll(): Flow<List<AlbumEntity>>

    @RawQuery(observedEntities = [AlbumEntity::class])
    fun observeAlbumsFor(query: SupportSQLiteQuery): Flow<List<AlbumEntity>>

    /** Local albums plus server albums with at least one downloaded track. */
    @Query(
        "SELECT * FROM albums WHERE sourceId = 'local' OR EXISTS " +
            "(SELECT 1 FROM tracks WHERE tracks.albumKey = albums.serverId AND tracks.path LIKE 'file:%') " +
            "ORDER BY year DESC, title"
    )
    fun observeAlbumsOffline(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun byId(id: Long): AlbumEntity?

    @Query("SELECT * FROM albums WHERE sourceId = :sourceId")
    suspend fun bySource(sourceId: String): List<AlbumEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(albums: List<AlbumEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(album: AlbumEntity)

    @Query("SELECT COUNT(*) FROM albums")
    suspend fun count(): Int

    @Query("DELETE FROM albums WHERE serverId = :serverId")
    suspend fun deleteByServerId(serverId: String)
}

@Dao
interface PendingScrobbleDao {
    @Insert
    suspend fun insert(scrobble: PendingScrobbleEntity)

    @Query("SELECT * FROM pending_scrobbles ORDER BY createdAt")
    suspend fun all(): List<PendingScrobbleEntity>

    @Query("DELETE FROM pending_scrobbles WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM pending_scrobbles")
    suspend fun count(): Int
}

data class PlaylistWithCount(
    val id: Long,
    val name: String,
    val coverPath: String?,
    val createdAt: Long,
    val trackCount: Int,
)

@Dao
interface PlaylistDao {
    @Query(
        "SELECT p.id AS id, p.name AS name, p.coverPath AS coverPath, p.createdAt AS createdAt, COUNT(pt.id) AS trackCount " +
        "FROM playlists p LEFT JOIN playlist_tracks pt ON pt.playlistId = p.id " +
        "GROUP BY p.id ORDER BY p.createdAt DESC"
    )
    fun observeAll(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    @Query("UPDATE playlists SET coverPath = :path WHERE id = :id")
    suspend fun setCoverPath(id: Long, path: String?)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :id")
    suspend fun deleteTracksFor(id: Long)

    /** Both deletes must succeed or neither — keeps counts consistent. */
    @Transaction
    suspend fun deletePlaylistCascade(id: Long) {
        deleteTracksFor(id)
        deletePlaylist(id)
    }

    /** Drops playlist rows pointing at tracks that no longer exist. */
    @Query("DELETE FROM playlist_tracks WHERE trackId NOT IN (SELECT id FROM tracks)")
    suspend fun deleteOrphanRows()

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :id")
    suspend fun maxPosition(id: Long): Int

    @Insert
    suspend fun insertTrack(track: PlaylistTrackEntity)

    @Query("DELETE FROM playlist_tracks WHERE id = :rowId")
    suspend fun removeTrack(rowId: Long)

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :id ORDER BY position, id")
    suspend fun rowsFor(id: Long): List<PlaylistTrackEntity>

    @Query("SELECT t.* FROM tracks t JOIN playlist_tracks pt ON pt.trackId = t.id WHERE pt.playlistId = :id ORDER BY pt.position, pt.id")
    suspend fun tracksFor(id: Long): List<TrackEntity>

    @Query(
        "SELECT t.* FROM tracks t JOIN playlist_tracks pt ON pt.trackId = t.id " +
        "WHERE pt.playlistId = :id ORDER BY pt.position, pt.id LIMIT 1"
    )
    suspend fun firstTrackFor(id: Long): TrackEntity?
}

@Dao
interface ThemeDao {
    @Query("SELECT * FROM custom_themes ORDER BY name")
    fun observeAll(): Flow<List<CustomThemeEntity>>

    @Upsert
    suspend fun upsert(theme: CustomThemeEntity)

    @Query("DELETE FROM custom_themes WHERE name = :name")
    suspend fun delete(name: String)
}
