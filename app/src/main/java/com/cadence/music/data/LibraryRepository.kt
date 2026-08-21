package com.cadence.music.data

import android.content.ContentUris
import android.provider.MediaStore
import com.cadence.music.data.db.AppDatabase
import com.cadence.music.data.db.TrackEntity
import com.cadence.music.data.prefs.LibraryMode
import com.cadence.music.data.prefs.Prefs
import com.cadence.music.data.source.LocalSource
import com.cadence.music.data.source.SubsonicSource
import kotlinx.coroutines.flow.Flow

class LibraryRepository(
    private val db: AppDatabase,
    private val local: LocalSource,
    val subsonic: SubsonicSource,
    private val prefs: Prefs,
) {
    fun tracks(): Flow<List<TrackEntity>> = db.trackDao().observeAll()

    suspend fun syncAll() {
        when (prefs.mode) {
            LibraryMode.LOCAL_ONLY -> syncLocal()
            LibraryMode.API_ONLY -> syncServer()
            LibraryMode.HYBRID -> { syncLocal(); syncServer() }
        }
    }

    suspend fun syncLocal() {
        val mediaStoreTracks = local.scan()
        val entities = mediaStoreTracks.map { t ->
            val mediaId = t.key.removePrefix("local:").toLong()
            TrackEntity(
                id = 0,
                sourceId = "local",
                serverId = t.key,
                title = t.title,
                artistId = null,
                albumId = null,
                path = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId
                ).toString(),
                durationMs = t.durationMs,
                trackNumber = 0,
            )
        }
        db.trackDao().insertAll(entities)
    }

    suspend fun syncServer(): Int {
        if (prefs.server == null) return 0
        val remote = subsonic.scan()
        val entities = remote.map { t ->
            TrackEntity(
                id = 0,
                sourceId = "subsonic",
                serverId = t.key,
                title = t.title,
                artistId = null,
                albumId = null,
                path = null,
                durationMs = t.durationMs,
                trackNumber = 0,
            )
        }
        db.trackDao().insertAll(entities)
        return entities.size
    }
}
