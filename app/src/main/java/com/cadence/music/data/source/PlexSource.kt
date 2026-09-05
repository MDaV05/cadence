package com.cadence.music.data.source

import com.cadence.music.data.prefs.ServerEntry

/** Pure plex.tv PIN helpers (network stays in the UI polling loop — Task 5). */
object PlexPin {
    fun requestUrl(): String = "https://plex.tv/api/v2/pins?strong=true"
    fun pollUrl(pinId: Long): String = "https://plex.tv/api/v2/pins/$pinId"
    fun resourcesUrl(): String =
        "https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1"
}

class PlexSource(private val entry: ServerEntry, private val deviceId: String) : MusicSource {
    override val id = "plex"

    private fun base(): String = entry.url.trimEnd('/')
    private fun token(): String = entry.token ?: ""

    fun partUrl(partKey: String): String =
        "${base()}$partKey${if (partKey.contains("?")) "&" else "?"}X-Plex-Token=${token()}"

    fun thumbUrl(thumb: String): String =
        "${base()}/photo/:/transcode?url=${java.net.URLEncoder.encode(thumb, "UTF-8").replace("%2F", "/")}&X-Plex-Token=${token()}"

    fun coverArtUrl(albumKey: String): String =
        thumbUrl((cachedThumb(albumKey.removePrefix("plex:")) ?: ""))

    fun downloadUrl(songId: String): String {
        val ratingKey = songId.removePrefix("plex:")
        // Direct play: downloads are the same direct-play Part URL.
        return partUrl(cachedPart(ratingKey) ?: "/library/metadata/$ratingKey/download")
    }

    override suspend fun streamUrl(track: Track): String? =
        track.streamUrl ?: cachedPart(track.key.removePrefix("plex:"))?.let { partUrl(it) }

    // scan(): no-op (library sync uses listAlbums+albumTracksByKey, same rationale as Task 2).
    override suspend fun scan(): List<Track> = emptyList()

    suspend fun ping(): Boolean =
        runCatching { get("identity") != null }.getOrDefault(false)

    suspend fun listAlbums(): List<Album> {
        val sections = get("library/sections")
            ?.optJSONObject("MediaContainer")
            ?.optJSONArray("Directory") ?: return emptyList()
        val musicKeys = (0 until sections.length()).mapNotNull { i ->
            val d = sections.getJSONObject(i)
            val type = d.optString("type", "")
            if (type == "music" || type == "artist") d.getString("key") else null
        }
        val out = mutableListOf<Album>()
        for (section in musicKeys) {
            var start = 0
            while (true) {
                val obj = get("library/sections/$section/all?type=9&X-Plex-Container-Start=$start&X-Plex-Container-Size=500")
                    ?: break
                val items = obj.optJSONObject("MediaContainer")?.optJSONArray("Metadata")
                    ?: break
                if (items.length() == 0) break
                for (i in 0 until items.length()) {
                    val a = items.getJSONObject(i)
                    val ratingKey = a.getString("ratingKey")
                    val thumb = a.optString("thumb", null)
                    thumbCache[ratingKey] = thumb
                    out += Album(
                        key = "plex:$ratingKey",
                        sourceId = id,
                        title = a.optString("title", "Unknown"),
                        artist = a.optString("parentTitle", ""),
                        year = a.optInt("year", 0).takeIf { it > 0 },
                    )
                }
                start += items.length()
            }
        }
        return out
    }

    suspend fun albumTracksByKey(albumKey: String): List<Track> {
        val albumRatingKey = albumKey.removePrefix("plex:")
        val obj = get("library/metadata/$albumRatingKey/children") ?: return emptyList()
        val items = obj.optJSONObject("MediaContainer")?.optJSONArray("Metadata")
            ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val m = items.getJSONObject(i)
            val ratingKey = m.optString("ratingKey", null) ?: return@mapNotNull null
            // Direct play: pick the first audio Part; transcode decision deferred (v1).
            val partKey = m.optJSONArray("Media")?.optJSONObject(0)
                ?.optJSONArray("Part")?.optJSONObject(0)?.optString("key", null)
            if (partKey != null) partCache[ratingKey] = partKey
            val parentKey = m.optString("parentRatingKey", albumRatingKey)
            Track(
                key = "plex:$ratingKey",
                sourceId = id,
                title = m.optString("title", "Unknown"),
                artist = m.optString("grandparentTitle", ""),
                album = m.optString("parentTitle", ""),
                albumKey = "plex:$parentKey",
                durationMs = m.optLong("duration", 0),
                localPath = null,
                streamUrl = partKey?.let { partUrl(it) },
            )
        }
    }

    override suspend fun search(query: String): List<Track> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val sections = get("library/sections")
            ?.optJSONObject("MediaContainer")
            ?.optJSONArray("Directory") ?: return emptyList()
        val musicKeys = (0 until sections.length()).mapNotNull { i ->
            val d = sections.getJSONObject(i)
            val type = d.optString("type", "")
            if (type == "music" || type == "artist") d.getString("key") else null
        }
        val out = mutableListOf<Track>()
        for (section in musicKeys) {
            val obj = get("library/sections/$section/all?type=10&title=$q&X-Plex-Container-Size=50")
                ?: continue
            val items = obj.optJSONObject("MediaContainer")?.optJSONArray("Metadata")
                ?: continue
            for (i in 0 until items.length()) {
                if (out.size >= 50) break
                val m = items.getJSONObject(i)
                val ratingKey = m.optString("ratingKey", null) ?: continue
                val partKey = m.optJSONArray("Media")?.optJSONObject(0)
                    ?.optJSONArray("Part")?.optJSONObject(0)?.optString("key", null)
                if (partKey != null) partCache[ratingKey] = partKey
                out += Track(
                    key = "plex:$ratingKey",
                    sourceId = id,
                    title = m.optString("title", "Unknown"),
                    artist = m.optString("grandparentTitle", ""),
                    album = m.optString("parentTitle", ""),
                    albumKey = m.optString("parentRatingKey", null)?.let { "plex:$it" },
                    durationMs = m.optLong("duration", 0),
                    localPath = null,
                    streamUrl = partKey?.let { partUrl(it) },
                )
            }
            if (out.size >= 50) break
        }
        return out
    }

    // Thin HTTP shell (Dispatchers.IO, 10s/30s timeouts, Accept: application/json
    // on every PMS call, token as X-Plex-Token query param, runCatching → null).
    // Server picking (Task 5 UI): parse resources JSON — connections[] entries
    // {uri, local, relay}; prefer first non-relay, fallback relay; manual URL
    // override always offered.
    // NOTE: setStarred intentionally absent — Plex starring unsupported v1;
    // repository never routes stars to plex (Task 4).
    protected suspend fun get(path: String): org.json.JSONObject? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val sep = if (path.contains("?")) "&" else "?"
                val conn = java.net.URL("${base()}/$path${sep}X-Plex-Token=${token()}")
                    .openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("X-Plex-Product", "Cadence")
                conn.setRequestProperty("X-Plex-Client-Identifier", deviceId)
                conn.setRequestProperty("X-Plex-Version", "0.2.0")
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }

    private val thumbCache = HashMap<String, String?>() // ratingKey → thumb path (memory only)
    private val partCache = HashMap<String, String?>() // ratingKey → Part key (memory only)
    private fun cachedThumb(ratingKey: String): String? = thumbCache[ratingKey]
    private fun cachedPart(ratingKey: String): String? = partCache[ratingKey]
}
