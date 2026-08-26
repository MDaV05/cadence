package com.cadence.music.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY title")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE serverId = :serverId LIMIT 1")
    suspend fun byServerId(serverId: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE albumKey = :albumKey")
    suspend fun byAlbumKey(albumKey: String): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Delete
    suspend fun delete(track: TrackEntity)

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayed = :now WHERE id = :id")
    suspend fun recordPlay(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE tracks SET path = :path WHERE id = :id")
    suspend fun setPath(id: Long, path: String)

    @Query("SELECT DISTINCT artistName FROM tracks WHERE artistName != '' ORDER BY artistName")
    fun observeArtistNames(): Flow<List<String>>

    @Query("SELECT * FROM tracks WHERE artistName = :name ORDER BY albumName, trackNumber")
    suspend fun byArtist(name: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE albumName = :name ORDER BY trackNumber")
    suspend fun byAlbum(name: String): List<TrackEntity>

    @Query(
        "SELECT albumName AS name, MIN(artistName) AS artistName, COUNT(*) AS trackCount " +
        "FROM tracks WHERE albumName != '' GROUP BY albumName ORDER BY name COLLATE NOCASE"
    )
    fun observeAlbumGroups(): Flow<List<AlbumGroup>>
}

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE trackServerId = :serverId AND sourceId = :sourceId")
    suspend fun byTrack(sourceId: String, serverId: String): DownloadEntity?
}

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums ORDER BY year DESC, title")
    fun observeAll(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun byId(id: Long): AlbumEntity?

    @Query("SELECT * FROM albums WHERE sourceId = :sourceId")
    suspend fun bySource(sourceId: String): List<AlbumEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(albums: List<AlbumEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(album: AlbumEntity)
}

data class PlaylistWithCount(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val trackCount: Int,
)

@Dao
interface PlaylistDao {
    @Query(
        "SELECT p.id AS id, p.name AS name, p.createdAt AS createdAt, COUNT(pt.id) AS trackCount " +
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
}
