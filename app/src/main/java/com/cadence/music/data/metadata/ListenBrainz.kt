package com.cadence.music.data.metadata

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ListenBrainz {

    private const val ENDPOINT = "https://api.listenbrainz.org/1/submit-listens"

    /** Fire-and-forget single listen submission. Returns true on 200. */
    fun submitBlocking(token: String, artist: String, title: String, album: String?): Boolean {
        return try {
            val payload = JSONObject().apply {
                put("listen_type", "single")
                put(
                    "payload",
                    JSONArray().put(
                        JSONObject().apply {
                            put(
                                "track_metadata",
                                JSONObject().apply {
                                    put("artist_name", artist)
                                    put("track_name", title)
                                    if (!album.isNullOrBlank()) put("release_name", album)
                                }
                            )
                        }
                    )
                )
            }
            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Token $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            try {
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                conn.responseCode == 200
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) { false }
    }
}
