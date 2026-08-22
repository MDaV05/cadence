package com.cadence.music.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY title")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: Long): TrackEntity?

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayed = :now WHERE id = :id")
    suspend fun recordPlay(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE tracks SET path = :path WHERE id = :id")
    suspend fun setPath(id: Long, path: String)

    @Query("DELETE FROM tracks WHERE albumKey = :albumKey")
    suspend fun deleteByAlbumKey(albumKey: String)

    @Query("SELECT DISTINCT artistName FROM tracks WHERE artistName != '' ORDER BY artistName")
    fun observeArtistNames(): Flow<List<String>>

    @Query("SELECT * FROM tracks WHERE artistName = :name ORDER BY albumName, trackNumber")
    suspend fun byArtist(name: String): List<TrackEntity>
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

@Dao
interface ArtistDao {
    @Query("SELECT * FROM artists ORDER BY name")
    fun observeAll(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun byId(id: Long): ArtistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(artists: List<ArtistEntity>)
}
