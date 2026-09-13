package com.cadence.music.data.metadata

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ArtistInfo(
    val bio: String?,
    val imageUrl: String?,
    // en-wiki article URL for attribution / "read more".
    val sourcePageUrl: String? = null,
)

object Wikipedia {

    private const val BASE = "https://en.wikipedia.org/api/rest_v1"
    internal const val USER_AGENT = "Cadence/0.1 ( https://github.com/MDaV05/cadence )"

    private fun encodePath(title: String): String =
        URLEncoder.encode(title.trim().replace(' ', '_'), "UTF-8").replace("+", "%20")

    private data class PageSummary(
        val title: String,
        val bio: String?,
        val imageUrl: String?,
        val pageUrl: String?,
    )

    private fun fetchPageSummary(title: String): PageSummary? {
        if (title.isBlank()) return null
        return try {
            val enc = encodePath(title)
            val conn = URL("$BASE/page/summary/$enc").openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            try {
                if (conn.responseCode != 200) return null
                // R3-15: cap the body so a hostile/corrupt response can't exhaust memory.
                val json = JSONObject(conn.inputStream.use { it.readNBytes(2 * 1024 * 1024) }.decodeToString())
                if (json.optString("type") != "standard") return null
                val extract = json.optString("extract", "").ifBlank { null }
                val img = json.optJSONObject("thumbnail")?.optString("source")
                    ?: json.optJSONObject("originalimage")?.optString("source")
                val pageUrl = json.optJSONObject("content_urls")
                    ?.optJSONObject("desktop")?.optString("page")?.ifBlank { null }
                PageSummary(
                    title = json.optString("title", title),
                    bio = extract,
                    imageUrl = img,
                    pageUrl = pageUrl,
                )
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) { null }
    }

    /**
     * Resolves the artist's Wikipedia bio and picture strictly through
     * MusicBrainz (canonical MBID -> en-wiki article). When MusicBrainz has no
     * such artist or link, this returns null on purpose — guessing from search
     * ranking served wrong pages (rockets, proteins, Greek letters) far too
     * often. The user-set artist picture override is the manual escape hatch.
     */
    fun artistInfoBlocking(name: String): ArtistInfo? {
        val clean = name.trim()
        if (clean.isBlank()) return null
        val title = musicBrainzPageTitle(clean) ?: return null
        val summary = fetchPageSummary(title) ?: return null
        return ArtistInfo(
            bio = summary.bio,
            imageUrl = summary.imageUrl,
            sourcePageUrl = summary.pageUrl,
        )
    }
}

// ---- MusicBrainz-first artist page resolution -------------------------------
// MusicBrainz keeps one canonical artist per MBID and links it to the exact
// Wikipedia article, so name collisions ("Justice" the band vs the concept)
// resolve correctly instead of relying on search ranking.

private const val MB_BASE = "https://musicbrainz.org/ws/2"

/** Pure: first artist MBID from an MB artist-search response, else null. */
fun parseMbArtistMbid(json: String?): String? = runCatching {
    val artists = JSONObject(json ?: return null).optJSONArray("artists") ?: return null
    (0 until artists.length())
        .map { artists.optJSONObject(it) }
        .firstOrNull { it != null && it.optString("id").isNotBlank() }
        ?.optString("id")
}.getOrNull()

/** Pure: the en-wiki page title from an MB artist url-rels lookup, else null.
 *  Handles both "wikipedia" relations and "wikidata" ones via the sitelink JSON. */
fun parseMbWikipediaTitle(json: String?): String? = runCatching {
    val relations = JSONObject(json ?: return null).optJSONArray("relations") ?: return null
    for (i in 0 until relations.length()) {
        val rel = relations.optJSONObject(i) ?: continue
        if (rel.optString("type") != "wikipedia") continue
        val resource = rel.optJSONObject("url")?.optString("resource").orEmpty()
        if (resource.startsWith("https://en.wikipedia.org/wiki/")) {
            val title = java.net.URLDecoder.decode(
                resource.removePrefix("https://en.wikipedia.org/wiki/"), "UTF-8",
            ).replace('_', ' ') // MediaWiki URLs use underscores; titles use spaces
            if (title.isNotBlank()) return@runCatching title
        }
    }
    null
}.getOrNull()

/** Pure: the en-wiki sitelink title from a Wikidata entity JSON, else null. */
fun parseWikidataSitelink(json: String?): String? = runCatching {
    val entities = JSONObject(json ?: return null).optJSONObject("entities") ?: return null
    val keys = entities.keys()
    while (keys.hasNext()) {
        val sitelinks = entities.getJSONObject(keys.next()).optJSONObject("sitelinks") ?: continue
        val en = sitelinks.optJSONObject("enwiki") ?: continue
        val title = en.optString("title")
        if (title.isNotBlank()) return@runCatching title
    }
    null
}.getOrNull()

private fun mbGet(url: String): String? = try {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.setRequestProperty("User-Agent", Wikipedia.USER_AGENT)
    conn.connectTimeout = 10_000
    conn.readTimeout = 15_000
    try {
        if (conn.responseCode != 200) null
        else conn.inputStream.use { it.readNBytes(2 * 1024 * 1024) }.decodeToString()
    } finally {
        conn.disconnect()
    }
} catch (_: Exception) { null }

/**
 * Resolves the canonical en-wiki page title for an artist name through
 * MusicBrainz: artist search -> MBID -> wikipedia/wikidata link. Null when any
 * step comes up empty (callers fall back to heuristic search).
 */
fun musicBrainzPageTitle(name: String): String? {
    val enc = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
    val mbid = parseMbArtistMbid(mbGet("$MB_BASE/artist?query=artist:%22$enc%22&fmt=json&limit=1"))
        ?: return null
    val rels = mbGet("$MB_BASE/artist/$mbid?inc=url-rels&fmt=json")
    parseMbWikipediaTitle(rels)?.let { return it }
    val qid = rels?.let { json ->
        runCatching {
            val relations = JSONObject(json).optJSONArray("relations")
            (0 until (relations?.length() ?: 0))
                .mapNotNull { relations?.optJSONObject(it) }
                .firstOrNull { it.optString("type") == "wikidata" }
                ?.optJSONObject("url")?.optString("resource")
                ?.takeIf { it.startsWith("https://www.wikidata.org/wiki/") }
                ?.removePrefix("https://www.wikidata.org/wiki/")
        }.getOrNull()
    } ?: return null
    return parseWikidataSitelink(mbGet("https://www.wikidata.org/wiki/Special:EntityData/$qid.json"))
}
