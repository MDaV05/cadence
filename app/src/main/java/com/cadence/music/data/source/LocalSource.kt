package com.cadence.music.data.source

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

class LocalSource(private val context: Context) : MusicSource {

    override val id = "local"

    override suspend fun scan(): List<Track> {
        val tracks = mutableListOf<Track>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        context.contentResolver.query(collection, projection, selection, null, null)?.use { c ->
            val idC = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, c.getLong(idC))
                tracks += Track(
                    key = "local:${c.getLong(idC)}",
                    sourceId = id,
                    title = c.getString(titleC) ?: "Unknown",
                    artist = c.getString(artistC) ?: "Unknown",
                    album = c.getString(albumC) ?: "Unknown",
                    durationMs = c.getLong(durC),
                    localPath = uri.toString(),
                )
            }
        }
        return tracks
    }

    override suspend fun search(query: String): List<Track> =
        scan().filter {
            it.title.contains(query, true) || it.artist.contains(query, true)
        }

    override suspend fun streamUrl(track: Track): String? = track.localPath
}
