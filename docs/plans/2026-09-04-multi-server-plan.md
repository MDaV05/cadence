# Multi-Server Backends Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect to multiple saved Subsonic/Jellyfin/Emby/Plex servers behind the unchanged `MusicSource` interface, with per-type auth (including Plex PIN) and namespaced track identity.

**Architecture:** Typed server entries in Prefs (JSON list); one shared Emby-like client core for Jellyfin+Emby, standalone Plex client; repository resolves entries by ID prefix and fans out sync; DB identity namespaced as `<entryId>:<remoteKey>` while `sourceId` stays the bare type so all mode filtering, SQL, and counts work untouched.

**Tech Stack:** HttpURLConnection + org.json (existing pattern), Room migration, JUnit4.

**Spec:** `docs/plans/2026-09-04-multi-server-backends-design.md` — the plan argues from the spec; executors read both.

## Global Constraints

- Single `:app` module, minSdk 26 — no new libraries, no ViewModel/Hilt.
- `MusicSource` interface (`id, scan, search, streamUrl`) does not change.
- `sourceId` stays the bare type (`local/subsonic/jellyfin/emby/plex`) — never namespaced.
- Fail-safe everywhere: bad auth/network/version → inline error text or skip, never crash/dialog.
- Never persist Plex/Jellyfin/Emby passwords (tokens only); Subsonic keeps password (protocol needs it).
- Commit per task, conventional lowercase; run `/ponytail-review` before each commit (repo gate).
- Verify each task: `sh gradlew :app:assembleDebug :app:testDebugUnitTest`.
- org.json parsing lives in thin fetch fns (Android stubs — not JVM-testable, same ruling as `fetchLatest()`); pure builders/parsers take plain data and ARE unit-tested.

---

### Task 1: Typed server model + legacy migration (TDD)

**Files:**
- Modify: `app/src/main/java/com/cadence/music/data/prefs/Prefs.kt`
- Test: `app/src/test/java/com/cadence/music/data/prefs/ServersTest.kt` (new)

**Interfaces:**
- Consumes: existing `ServerConfig`, `LibraryMode`
- Produces: `ServerType` enum, `ServerEntry` data class, `Prefs.servers: List<ServerEntry>`,
  `Prefs.saveServers()`, `Prefs.entry(id): ServerEntry?` — used by Tasks 2–5

- [ ] **Step 1: Write the failing test**

```kotlin
package com.cadence.music.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServersTest {

    @Test
    fun `entry json round-trips all fields`() {
        val e = ServerEntry(
            id = "a1", type = ServerType.JELLYFIN, url = "https://box:8096",
            user = "u", password = null, token = "tok", userId = "uid1", active = true,
        )
        assertEquals(e, ServerEntry.fromJson(e.toJson()))
    }

    @Test
    fun `nulls survive round-trip`() {
        val e = ServerEntry("p", ServerType.SUBSONIC, "http://h", "u", "pw", null, null, false)
        val back = ServerEntry.fromJson(e.toJson())
        assertEquals(null, back.token)
        assertEquals(null, back.userId)
        assertEquals(false, back.active)
    }

    @Test
    fun `type parses strictly`() {
        assertEquals(ServerType.PLEX, ServerType.valueOf("PLEX"))
        assertNull(runCatching { ServerType.valueOf("plex") }.getOrNull())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sh gradlew :app:testDebugUnitTest --tests "com.cadence.music.data.prefs.ServersTest" 2>&1 | tail -3`
Expected: FAIL with "unresolved reference"

- [ ] **Step 3: Write minimal implementation** (in `Prefs.kt`, next to `ServerConfig`)

```kotlin
enum class ServerType { SUBSONIC, JELLYFIN, EMBY, PLEX }

data class ServerEntry(
    val id: String,
    val type: ServerType,
    val url: String,
    val user: String,
    val password: String? = null, // subsonic only; jelly/emby/plex use token
    val token: String? = null,
    val userId: String? = null, // jelly/emby remote user id
    val active: Boolean = true,
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("id", id).put("type", type.name).put("url", url).put("user", user)
        .put("password", password).put("token", token).put("userId", userId).put("active", active)

    companion object {
        fun fromJson(o: org.json.JSONObject): ServerEntry = ServerEntry(
            id = o.getString("id"),
            type = ServerType.valueOf(o.getString("type")),
            url = o.getString("url"),
            user = o.optString("user", ""),
            password = o.optString("password", null),
            token = o.optString("token", null),
            userId = o.optString("userId", null),
            active = o.optBoolean("active", true),
        )
    }
}
```

Note: `org.json.JSONObject.put(String, null)` WRITES `JSONObject.NULL` (not absence), and
`optString(key, null)` returns null for both absence and NULL — round-trip holds. This is why
the nulls test above passes; do not "simplify" with `?: ""`.

Prefs accessors (next to the existing `server` property):

```kotlin
var servers: List<ServerEntry>
    get() = runCatching {
        val arr = org.json.JSONArray(sp.getString("servers_json", "[]") ?: "[]")
        (0 until arr.length()).map { ServerEntry.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    set(value) = sp.edit().putString(
        "servers_json",
        org.json.JSONArray(value.map { it.toJson() }.toList()).toString(),
    ).apply()

fun entry(id: String): ServerEntry? = servers.firstOrNull { it.id == id }
```

Migration (runs once, inside the `servers` getter is wrong — do it explicitly; place in `Prefs` init-like path is overkill. Simplest: lazy one-shot inside the getter before parsing):

```kotlin
// One-shot: legacy single server becomes entry "primary", then legacy keys are dropped.
private fun migrateLegacyServer() {
    if (sp.contains("servers_json")) return
    val url = sp.getString("server_url", null) ?: return
    servers = listOf(
        ServerEntry(
            id = "primary", type = ServerType.SUBSONIC, url = url,
            user = sp.getString("server_user", "") ?: "",
            password = sp.getString("server_pass", null),
        )
    )
    sp.edit().remove("server_url").remove("server_user").remove("server_pass").apply()
}
```

Call `migrateLegacyServer()` as the first line of the `servers` getter. Keep the legacy `server`
property (other tasks' callers still use it until Task 4 rewires them) — do NOT delete it here.

- [ ] **Step 4: Run tests to verify they pass**

Run: `sh gradlew :app:testDebugUnitTest --tests "com.cadence.music.data.prefs.ServersTest" 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/cadence/music/data/prefs/Prefs.kt app/src/test/java/com/cadence/music/data/prefs/ServersTest.kt
git commit -m "feat: typed multi-server model with legacy migration"
```

---

### Task 2: Emby-like core + Jellyfin + Emby sources (TDD pure parts)

**Files:**
- Create: `app/src/main/java/com/cadence/music/data/source/EmbyLike.kt`
- Create: `app/src/main/java/com/cadence/music/data/source/JellyfinSource.kt`
- Create: `app/src/main/java/com/cadence/music/data/source/EmbySource.kt`
- Test: `app/src/test/java/com/cadence/music/data/source/EmbyLikeTest.kt` (new)

**Interfaces:**
- Consumes: Task 1 (`ServerEntry`, `ServerType`)
- Produces: `JellyfinSource(entry): MusicSource`-compatible class, `EmbySource(entry)`,
  exact public surface below — used by Task 4

Public surface each source MUST expose (mirrors what the repo uses from `SubsonicSource`):
`val id: String` (= `"jellyfin"` / `"emby"` — bare type, never namespaced),
`suspend fun ping(): Boolean`, `suspend fun listAlbums(): List<Album>`,
`suspend fun albumTracksByKey(albumKey: String): List<Track>`,
`override suspend fun search(query: String): List<Track>`,
`override suspend fun streamUrl(track: Track): String?`,
`fun downloadUrl(songId: String): String`, `fun coverArtUrl(albumKey: String): String`,
`suspend fun setStarred(songId: String, starred: Boolean)`,
`suspend fun authenticate(): Boolean` (exchanges user+password for token, returns success).

Key convention (matches Subsonic `"sub:<id>"` dialect): in-memory `Track.key = "jelly:<remoteId>"`
/ `"emby:<remoteId>"`, `Album.key` likewise; all public methods above take these prefixed keys
and strip internally. DB namespacing (`<entryId>:` prefix) happens in Task 4, NOT here.

- [ ] **Step 1: Write the failing test** (pure URL/header builders only — no org.json, no network)

```kotlin
package com.cadence.music.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbyLikeTest {

    private val entry = com.cadence.music.data.prefs.ServerEntry(
        id = "e1", type = com.cadence.music.data.prefs.ServerType.JELLYFIN,
        url = "https://box:8096/", user = "u", token = "tok", userId = "uid",
    )

    @Test
    fun `stream url carries api key`() {
        val s = JellyfinSource(entry)
        assertEquals("https://box:8096/Audio/abc/stream?api_key=tok", s.streamUrlFor("abc"))
    }

    @Test
    fun `cover art url`() {
        val s = JellyfinSource(entry)
        assertEquals("https://box:8096/Items/abc/Images/Primary?api_key=tok", s.coverArtUrl("jelly:abc"))
    }

    @Test
    fun `auth header shape`() {
        val h = EmbyLikeAuthHeader(client = "Cadence", version = "0.2.0", deviceId = "d1", token = null)
        assertTrue(h.startsWith("MediaBrowser "))
        assertTrue(h.contains("Client=\"Cadence\""))
    }

    @Test
    fun `emby and jellyfin share paths`() {
        val j = JellyfinSource(entry)
        val e = EmbySource(entry.copy(type = com.cadence.music.data.prefs.ServerType.EMBY))
        assertEquals(j.streamUrlFor("abc"), e.streamUrlFor("abc").replace("box:8096", "box:8096"))
    }

    @Test
    fun `album track keys carry prefix`() {
        assertTrue("jelly:abc".removePrefix("jelly:") == "abc")
    }
}
```

(The last test pins the key-strip convention; real mapping is covered by Task 6 device pass.)

- [ ] **Step 2: Run test to verify it fails**

Run: `sh gradlew :app:testDebugUnitTest --tests "com.cadence.music.data.source.EmbyLikeTest" 2>&1 | tail -3`
Expected: FAIL with "unresolved reference"

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.cadence.music.data.source

import com.cadence.music.data.prefs.ServerEntry

/** Value of the X-Emby-Authorization header (both Jellyfin and Emby accept it). */
fun EmbyLikeAuthHeader(client: String, version: String, deviceId: String, token: String?): String =
    buildString {
        append("MediaBrowser Client=\"$client\", Device=\"Android\", DeviceId=\"$deviceId\", Version=\"$version\"")
        if (token != null) append(", Token=\"$token\"")
    }

/**
 * Shared Jellyfin/Emby client core. Subclasses differ only in [sourceId]/key prefix.
 * Network + org.json stay in thin private fns (same ruling as SubsonicSource.get);
 * URL builders are public for tests.
 */
abstract class EmbyLikeSource(protected val entry: ServerEntry) : MusicSource {

    protected fun base(): String = entry.url.trimEnd('/')
    protected fun token(): String = entry.token ?: ""
    protected fun uid(): String = entry.userId ?: ""

    /** POST /Users/AuthenticateByName — exchanges user+password for token. */
    abstract suspend fun authenticate(): Boolean

    suspend fun ping(): Boolean =
        runCatching { get("Users/${uid()}") != null }.getOrDefault(false)

    fun streamUrlFor(remoteId: String): String = "${base()}/Audio/$remoteId/stream?api_key=${token()}"
    fun downloadUrl(songId: String): String =
        "${base()}/Items/${songId.removePrefix(prefix())}/Download?api_key=${token()}"
    fun coverArtUrl(albumKey: String): String =
        "${base()}/Items/${albumKey.removePrefix(prefix())}/Images/Primary?api_key=${token()}"

    protected abstract fun prefix(): String // "jelly:" or "emby:"

    override suspend fun streamUrl(track: Track): String? =
        track.streamUrl ?: track.key.removePrefix(prefix()).let { streamUrlFor(it) }

    suspend fun setStarred(songId: String, starred: Boolean) {
        val id = songId.removePrefix(prefix())
        if (starred) post("Users/${uid()}/FavoriteItems/$id") else delete("Users/${uid()}/FavoriteItems/$id")
    }

    // Thin HTTP shell (mirrors SubsonicSource.get: Dispatchers.IO, timeouts, runCatching).
    // Albums: GET Users/{uid}/Items?Recursive=true&IncludeItemTypes=MusicAlbum&SortBy=SortName&Fields=ProductionYear
    //   paginate with StartIndex/Limit=500 until empty; Album.key = "<prefix><Id>".
    // Tracks: GET Users/{uid}/Items?ParentId={albumId}&Recursive=true&IncludeItemTypes=Audio&Fields=RunTimeTicks,IndexNumber
    //   durationMs = RunTimeTicks/10000; Track.key = "<prefix><Id>", albumKey = "<prefix><AlbumId>".
    // Search: GET Users/{uid}/Items?Recursive=true&IncludeItemTypes=Audio&SearchTerm={q}&Limit=50.
    protected suspend fun get(path: String): org.json.JSONObject? = TODO("implement per plan")
    protected suspend fun post(path: String, body: String = ""): Boolean = TODO("implement per plan")
    protected suspend fun delete(path: String): Boolean = TODO("implement per plan")
}
```

NO — `TODO()` in a plan is a plan failure ("implement later" by another name). Full bodies required:

```kotlin
    protected suspend fun get(path: String): org.json.JSONObject? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val conn = java.net.URL("${base()}/$path").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("X-Emby-Authorization", EmbyLikeAuthHeader("Cadence", "0.2.0", deviceId(), null))
                if (token().isNotEmpty()) conn.setRequestProperty("X-Emby-Token", token())
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }
```

`deviceId()`: `Settings.Secure.ANDROID_ID` needs Context — `EmbyLikeSource` has none. RULING
(in-plan): pass `deviceId: String` as a constructor param (repository supplies
`Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)` once).
Constructor: `EmbyLikeSource(entry: ServerEntry, deviceId: String)`.

`post`/`delete`: same shape, `conn.requestMethod = "POST"/"DELETE"`, empty body, return
`responseCode in 200..299`. `authenticate()`: POST `Users/AuthenticateByName` with JSON body
`{"Username": entry.user, "Pw": entry.password ?: ""}` + auth header; on 200 parse
`AccessToken` + `User.Id` — BUT the entry is immutable and the token must be persisted.
RULING (in-plan): `authenticate()` returns `Pair<String, String>?` (token, userId) or null;
the CALLER (Task 5 UI) writes them back via `prefs.servers = ...copy(token, userId)`. The source
never writes prefs.

`JellyfinSource(entry, deviceId) : EmbyLikeSource(entry, deviceId)`:
`override val id = "jellyfin"`, `override fun prefix() = "jelly:"`, inherits everything
(Jellyfin ≥10.8 accepts the same paths; document any version quirk found on device in Task 6).

`EmbySource(entry, deviceId) : EmbyLikeSource(entry, deviceId)`:
`override val id = "emby"`, `override fun prefix() = "emby:"`.

`scan()` (interface): return emptyList() (library sync uses listAlbums+albumTracksByKey, same as
Subsonic — check `SubsonicSource.scan` callers: none in repo; keep the no-op with a comment).

- [ ] **Step 4: Run tests to verify they pass**

Run: `sh gradlew :app:testDebugUnitTest --tests "com.cadence.music.data.source.EmbyLikeTest" 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/cadence/music/data/source/EmbyLike.kt app/src/main/java/com/cadence/music/data/source/JellyfinSource.kt app/src/main/java/com/cadence/music/data/source/EmbySource.kt app/src/test/java/com/cadence/music/data/source/EmbyLikeTest.kt
git commit -m "feat: jellyfin and emby sources on shared client core"
```

---

### Task 3: Plex source + PIN auth (TDD pure parts)

**Files:**
- Create: `app/src/main/java/com/cadence/music/data/source/PlexSource.kt` (includes `PlexPin` pure helpers)
- Test: `app/src/test/java/com/cadence/music/data/source/PlexTest.kt` (new)

**Interfaces:**
- Consumes: Task 1 (`ServerEntry`)
- Produces: `PlexSource(entry, deviceId)` with the Task 2 public surface (`id = "plex"`,
  `ping/listAlbums/albumTracksByKey/search/streamUrl/downloadUrl/coverArtUrl`, NO setStarred —
  Plex starring unsupported v1, documented), `PlexPin` helpers — used by Tasks 4–5

Key convention: `Track.key = "plex:<ratingKey>"`, `Album.key = "plex:<ratingKey>"`.
All PMS calls send `Accept: application/json`; token rides as `X-Plex-Token` query param
(stable URLs keep the stream cache working). Client headers on plex.tv calls:
`X-Plex-Product: Cadence`, `X-Plex-Client-Identifier: <deviceId>`,
`X-Plex-Version: 0.2.0`, `Accept: application/json`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.cadence.music.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexTest {

    @Test
    fun `pin request targets plex tv v2`() {
        assertEquals("https://plex.tv/api/v2/pins?strong=true", PlexPin.requestUrl())
    }

    @Test
    fun `stream url carries token`() {
        val s = PlexSource(
            com.cadence.music.data.prefs.ServerEntry(
                id = "p1", type = com.cadence.music.data.prefs.ServerType.PLEX,
                url = "http://nas:32400", user = "", token = "tok",
            ),
            deviceId = "d1",
        )
        assertTrue(s.partUrl("/library/parts/7/123/file").contains("X-Plex-Token=tok"))
    }

    @Test
    fun `thumb url prefixes server`() {
        val s = PlexSource(
            com.cadence.music.data.prefs.ServerEntry(
                id = "p1", type = com.cadence.music.data.prefs.ServerType.PLEX,
                url = "http://nas:32400/", user = "", token = "tok",
            ),
            deviceId = "d1",
        )
        assertEquals("http://nas:32400/photo/:/transcode?url=/thumb&X-Plex-Token=tok", s.thumbUrl("/thumb"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sh gradlew :app:testDebugUnitTest --tests "com.cadence.music.data.source.PlexTest" 2>&1 | tail -3`
Expected: FAIL with "unresolved reference"

- [ ] **Step 3: Write minimal implementation**

```kotlin
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

    fun partUrl(partKey: String): String = "$base$partKey${if (partKey.contains("?")) "&" else "?"}X-Plex-Token=${token()}"
    fun thumbUrl(thumb: String): String =
        "${base()}/photo/:/transcode?url=${java.net.URLEncoder.encode(thumb, "UTF-8")}&X-Plex-Token=${token()}"

    fun coverArtUrl(albumKey: String): String =
        thumbUrl((cachedThumb(albumKey.removePrefix("plex:")) ?: ""))

    // Thin HTTP shell (Dispatchers.IO, 10s/30s timeouts, plex headers, runCatching → null/false).
    // Albums: GET {base}/library/sections → filter type=music → GET
    //   /library/sections/{key}/all?type=9, paginate X-Plex-Container-Start/Size=500.
    //   Album.key = "plex:<ratingKey>".
    // Tracks: GET /library/sections/{key}/all?type=10&album.id={albumRatingKey} (or
    //   /library/metadata/{albumKey}/children), durationMs = Metadata.duration.
    //   Track.key = "plex:<ratingKey>", albumKey = "plex:<parentRatingKey>".
    // Search: GET /library/sections/{key}/all?type=10&title=<q> per music section (cap 50).
    // Stream: Metadata.Media.Part.key → partUrl(); pick first audio Part (direct play;
    //   transcode decision deferred — document in code comment).
    // Download: same partUrl (decision: direct).
    // Server picking (Task 5 UI): parse resources JSON — connections[] entries {uri, local,
    //   relay}; prefer first non-relay, fallback relay; manual URL override always offered.
    // ping(): GET {base}/identity with token → 200.
    // scan(): emptyList() (same no-op rationale as Task 2).
    // setStarred: UNSUPPORTED v1 — repository never routes stars to plex (Task 4).

    private val thumbCache = HashMap<String, String?>() // ratingKey → thumb path (memory only)
    private fun cachedThumb(ratingKey: String): String? = thumbCache[ratingKey]
}
```

`thumbCache` population: album listing stores `thumb` per ratingKey (memory-only; rebuilt per
sync — art URLs need a live server anyway). Track rows reuse the album thumb via their albumKey.

- [ ] **Step 4: Run tests to verify they pass**

Run: `sh gradlew :app:testDebugUnitTest --tests "com.cadence.music.data.source.PlexTest" 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/cadence/music/data/source/PlexSource.kt app/src/test/java/com/cadence/music/data/source/PlexTest.kt
git commit -m "feat: plex source with pin auth helpers"
```

---

### Task 4: Repository fan-out, routing, namespacing + DB migration

**Files:**
- Modify: `app/src/main/java/com/cadence/music/data/LibraryRepository.kt`
- Modify: `app/src/main/java/com/cadence/music/data/db/AppDatabase.kt` (v8→v9 data migration)
- Modify: `app/src/main/java/com/cadence/music/CadenceApp.kt` (container wiring)
- Modify: `app/src/main/java/com/cadence/music/data/metadata/ArtResolver.kt` (route via library)
- Modify: `app/src/main/java/com/cadence/music/data/downloads/DownloadWorker.kt` (route download URL)
- Modify: `app/src/main/java/com/cadence/music/playback/PlaybackService.kt` (resume prefix-on-miss)
- Test: build green + existing suite (naming migration verified by Task 6 device checklist)

**Interfaces:**
- Consumes: Tasks 1–3 (entries, all four source classes)
- Produces: namespaced identity (`<entryId>:<remoteKey>`), routed `streamUrlFor/downloadUrlFor/
  coverArtFor/setStarredFor/pingEntry`, fanned-out `syncAll` — used by Task 5

Naming (exact, no deviations):
- DB `tracks.serverId` / `albums.serverId` / `downloads.trackServerId` / `Track.key` /
  `Album.key` / mediaIds = `"<entryId>:<remoteKey>"` where remoteKey keeps today's shape
  (`"sub:ID"`, `"jelly:ID"`, `"emby:ID"`, `"plex:ID"`, `"local:ID"`).
- `fun entryForServerId(serverId: String): ServerEntry?` splits on the FIRST `':'` and looks
  up `prefs.entry(id)` (top-level in LibraryRepository.kt, pure enough to eyeball; covered by
  Task 6 checklist, not unit-testable without Prefs).
- `fun remoteKey(serverId: String, entry: ServerEntry): String = serverId.removePrefix("${entry.id}:")`.
- Legacy single server migrates to entry id `"primary"` (Task 1).

- [ ] **Step 1: Repository source cache + routing** (in `LibraryRepository`)

```kotlin
private val deviceId: String by lazy {
    android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "cadence"
}

private fun sourceFor(entry: ServerEntry): Any = when (entry.type) {
    ServerType.SUBSONIC -> SubsonicSource { entry }
    ServerType.JELLYFIN -> JellyfinSource(entry, deviceId)
    ServerType.EMBY -> EmbySource(entry, deviceId)
    ServerType.PLEX -> PlexSource(entry, deviceId)
}
```

(No cache map — sources are cheap holders of entry+deviceId; construct per call. Fewer moving
parts than a synchronized cache; GC pressure negligible next to HTTP.)

Routing (replace ALL direct `subsonic.*` uses in repo + expose for UI/workers/playback):

```kotlin
/** Fresh authenticated stream URL for any namespaced in-memory Track. */
suspend fun streamUrlFor(track: Track): String? {
    if (track.localPath != null) return track.localPath
    val key = track.key
    if (key.startsWith("local:")) return track.localPath
    val entry = entryForServerId(key.substringBefore('|').let { key }) ?: return null
    ...
}
```

NO — overcomplicated. Exact form: entry id is the segment before the FIRST ':'. `Track.key`
for API tracks is `"<entryId>:<remoteKey>"` (built at toTrack() time from the namespaced
serverId — see Step 3). So:

```kotlin
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

fun coverArtFor(albumKey: String): String? {
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
        // Plex: starring unsupported v1 — silent no-op (spec).
    }
}

suspend fun pingEntry(entry: ServerEntry): Boolean = when (val s = sourceFor(entry)) {
    is SubsonicSource -> s.ping()
    is EmbyLikeSource -> s.ping()
    is PlexSource -> s.ping()
    else -> false
}
```

`toggleStar()` keeps its local-DB flip, then calls `setStarredFor` INSTEAD of the current
subsonic-only block. `enqueueDownload`/`enqueueDownloads` stay (source check `!= "subsonic"`
widens: only `"local"` is non-downloadable — change guard to `if (track.sourceId == "local") return`).

- [ ] **Step 2: Fan-out `syncAll`/`syncServer`**

```kotlin
suspend fun syncAll() {
    // Local part honors mode as today (LOCAL_ONLY/HYBRID sync local files).
    if (prefs.mode != LibraryMode.API_ONLY) syncLocal()
    for (entry in prefs.servers.filter { it.active }) {
        // One bad server must not abort the rest (same isolation as per-album handling).
        runCatching { syncServerEntry(entry) }.getOrDefault(SyncResult(0, 0))
    }
}
```

Refactor today's `syncServer()` into `syncServerEntry(entry: ServerEntry): SyncResult`:
same body, with `subsonic` replaced by `sourceFor(entry)`-dispatched calls. The current body
calls `subsonic.listAlbums()` + `subsonic.albumTracksByKey()` and writes `sourceId = "subsonic"`,
`serverId = t.key`. New body (per entry, `sid` = `entry.id`):

- `val albums = when (val s = sourceFor(entry)) { is SubsonicSource -> s.listAlbums(); is EmbyLikeSource -> s.listAlbums(); is PlexSource -> s.listAlbums(); else -> emptyList() }`
- `sourceId = when (entry.type) { SUBSONIC -> "subsonic"; JELLYFIN -> "jellyfin"; EMBY -> "emby"; PLEX -> "plex" }` (bare type — mode filtering untouched).
- `serverId = "${entry.id}:${t.key}"`, `albumKey = t.albumKey?.let { "${entry.id}:$it" }`, albums table `serverId` likewise.
- `known` lookups switch from `bySource("subsonic")` (AlbumDao) to in-memory filter on the
  namespaced prefix (`.filter { it.serverId.startsWith("${entry.id}:") }`), same for
  `byAlbumKey` (call with namespaced key — pass `"${entry.id}:${album.key}"`).
- Keep: incremental `remoteCreated` skip, fetch-before-write, transactions, pruning
  (`pruneDeletedAlbums` gains an entry param; same for its call).
- `prefs.server == null` early-return becomes `if (prefs.servers.none { it.active }) return SyncResult(0, 0)`
  (mode no longer gates WHICH api syncs — mode gates local-vs-server inclusion at display;
  sync mirrors all active servers. RULING in-plan: sync scope = active entries, independent of
  LibraryMode. Display filtering stays mode-driven as today.)

Delete the old no-arg `syncServer()` AFTER the UI task stops calling it — NO, Task 5 rewrites
ServerTab. Order problem: Screens.kt save&sync calls `syncServer()`. RULING (in-plan): keep a
deprecated one-line `suspend fun syncServer(): SyncResult = syncAll().let { SyncResult(0, 0) }`?
That lies about counts. Better: keep `syncServer()` delegating to the FIRST active entry for
EXACTLY one task-cycle, mark `@Deprecated("Task 5 rewrites callers")`, delete in Task 5.
Do that.

`syncLocal()` unchanged (writes `sourceId = "local"`, keys already `"local:<id>"`).

- [ ] **Step 3: Container + downstream rewiring** (`CadenceApp.kt`, `ArtResolver.kt`,
  `DownloadWorker.kt`, `PlaybackService.kt`)

  - `AppContainer`: DELETE `val subsonic` and `ArtResolver(subsonic)` → `ArtResolver(library)`
    (ArtResolver change below). `resolveStreamUrl = { track -> library.streamUrlFor(track) }`.
    `player`/`submitScrobble`/`onTrackPlayed` (opaque mediaIds) unchanged.
  - `ArtResolver(private val library: LibraryRepository)`: replace the subsonic branch with
    `if (track.sourceId != "local" && track.albumKey != null) return library.coverArtFor(track.albumKey)`.
    Keep local + MusicBrainz fallback untouched. Update the class KDoc (drop "Subsonic").
  - `DownloadWorker.doWork`: replace `library.subsonic.downloadUrl(songId, ...)` with
    `library.downloadUrlFor(track.serverId, prefsFormat, bitrate) ?: return Result.failure()`;
    DELETE the `songId = ...removePrefix("sub:")` line (routing strips internally).
  - `PlaybackService.toMediaItem`: replace `container.subsonic.streamUrl(track)` with
    `container.library.streamUrlFor(track)`; DELETE the now-dead `Track(...)` import if unused.
  - `PlaybackService.buildResumeQueue`: after `byServerId(ids.optString(i))` miss, retry with
    `"primary:" + id` when the id contains no `':'` (legacy pre-namespacing resume data,
    self-cleaning, no version flags):
    ```kotlin
    val rawId = ids.optString(i)
    var entity = withContext(Dispatchers.IO) {
        runCatching { container.database.trackDao().byServerId(rawId) }.getOrNull()
    }
    if (entity == null && !rawId.contains(':')) {
        entity = withContext(Dispatchers.IO) {
            runCatching { container.database.trackDao().byServerId("primary:$rawId") }.getOrNull()
        }
    }
    entity ?: continue
    ```
    (Restructure the existing `?: continue` line accordingly.)

- [ ] **Step 4: DB migration v8→v9** (`AppDatabase.kt`; NO schema change — data-only)

```kotlin
private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Single-server legacy rows gain the "primary" entry prefix; local rows untouched.
        db.execSQL("UPDATE tracks SET serverId = 'primary:' || serverId WHERE sourceId != 'local'")
        db.execSQL("UPDATE tracks SET albumKey = 'primary:' || albumKey WHERE albumKey IS NOT NULL AND sourceId != 'local'")
        db.execSQL("UPDATE albums SET serverId = 'primary:' || serverId")
        db.execSQL("UPDATE downloads SET trackServerId = 'primary:' || trackServerId")
    }
}
```

Bump `@Database version = 8` → `9`, add `MIGRATION_8_9` to `addMigrations(...)`.
Then build once and commit the generated `app/schemas/.../9.json` (precedent: "data: room schema
v8 export" — KSP writes it on build; verify the file exists before committing).

- [ ] **Step 5: Verify**

Run: `sh gradlew :app:assembleDebug :app:testDebugUnitTest 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/cadence/music/data/LibraryRepository.kt app/src/main/java/com/cadence/music/data/db/AppDatabase.kt app/src/main/java/com/cadence/music/CadenceApp.kt app/src/main/java/com/cadence/music/data/metadata/ArtResolver.kt app/src/main/java/com/cadence/music/data/downloads/DownloadWorker.kt app/src/main/java/com/cadence/music/playback/PlaybackService.kt app/schemas/
git commit -m "feat: namespaced multi-server sync and routing"
```

---

### Task 5: Server list UI + type picker + auth forms + About support

**Files:**
- Modify: `app/src/main/java/com/cadence/music/ui/Screens.kt` (ServerTab rewrite; About support section)
- Test: build green + manual checklist (Step 5)

**Interfaces:**
- Consumes: Tasks 1–4 (entries, `pingEntry`, `authenticate()` pair-return, `PlexPin`, routing).
  Deletes the deprecated `syncServer()` shim from Task 4.

**Files:**
- Modify: `app/src/main/java/com/cadence/music/ui/Screens.kt`
- Test: build + manual checklist

- [ ] **Step 1: Rewrite ServerTab as a managed list** (replace the single-server form; keep the
  Library-mode radio block EXACTLY as-is below it)

```kotlin
@Composable
private fun ServerTab(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf(container.prefs.servers) }
    var showPicker by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var errId by remember { mutableStateOf<String?>(null) }

    fun refresh() { servers = container.prefs.servers }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Servers") }
        items(servers, key = { it.id }) { e ->
            SettingRow(
                title = "${e.type.name.lowercase().replaceFirstChar { it.uppercase() }} • ${e.url}",
                subtitle = if (e.active) "Active" else "Disabled",
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = e.active,
                            onCheckedChange = {
                                container.prefs.servers = servers.map {
                                    if (it.id == e.id) it.copy(active = it) else it
                                }
                                refresh()
                            },
                        )
                        IconButton(onClick = {
                            container.prefs.servers = servers.filter { it.id != e.id }
                            refresh()
                            scope.launch { runCatching { container.library.syncAll() } }
                        }) { Icon(Icons.Filled.Delete, "Remove server") }
                    }
                },
            )
            if (errId == e.id) {
                Text("Couldn't reach this server — check URL and credentials.", ...)
            }
        }
        item {
            TextButton(onClick = { showPicker = true }, ...) { Text("+ Add server") }
        }
        // ... existing Library-mode radio block UNCHANGED below ...
    }
    if (showPicker) ServerTypePicker(onPick = { /* open AddServerSheet(type) */ }, onDismiss = { showPicker = false })
}
```

Adapt to ACTUAL `SettingRow`/`SectionHeader` signatures (read them first — same instruction as the
updates plan). `Switch`/`Delete` icon imports as needed. Delete-confirm: wrap the remove in a
confirm `AlertDialog` (title "Remove server?", confirm "Remove") — do not delete on single tap.

- [ ] **Step 2: Type picker + add sheet**

  - `ServerTypePicker`: dialog listing Subsonic / Jellyfin / Emby / Plex (one-line subtitle each:
    "Navidrome, Gonic…", "Jellyfin servers", "Emby servers", "plex.tv login").
  - `AddServerSheet(type)`: URL + user + password fields for SUBSONIC/JELLYFIN/EMBY;
    "Save & test" → busy spinner → for SUBSONIC build `SubsonicSource { candidate }` and `ping()`;
    for JELLYFIN/EMBY call `source.authenticate()` → on success persist
    `entry.copy(token = t, userId = u, password = null)`; on failure inline error text
    ("Couldn't connect — check URL and credentials."). New entry id =
    `java.util.UUID.randomUUID().toString().take(8)` (no ':' ever — UUID hex has none).
    After save: `refresh()` + `scope.launch { runCatching { container.library.syncAll() } }`.
  - Plex add flow: "Connect with Plex" button → request PIN (pure `PlexPin.requestUrl()` +
    thin POST, same HttpURLConnection pattern) → show 4-char code + "Approve at plex.tv/link"
    button (browser intent) + polling spinner (`delay(2000)` loop, 120s timeout, cancellable on
    dismiss) → on token: fetch resources (`PlexPin.resourcesUrl()`), list server names →
    tap one (or "Enter URL manually" field) → persist entry → sync. Timeout/cancel → inline
    error, no entry saved.

- [ ] **Step 3: Delete the deprecated `syncServer()` shim** in `LibraryRepository.kt` and fix any
  remaining callers (there should be none after this rewrite — verify with
  `rg -n "syncServer\(\)" app/src/main` expecting zero hits outside the deleted def).

- [ ] **Step 4: About support buttons** (in `AboutTab`, new "Support Cadence" section above
  Diagnostics — exact addresses, tap-to-copy + Toast, mirroring the copy-debug-info pattern):

```kotlin
item { SectionHeader("Support Cadence") }
listOf(
    "BNB Smart Chain" to "0x57Ff65FB4b773F15BdfB507086facd28d8D7d049",
    "Bitcoin" to "bc1qeepyu36y79jw0nn4fyrkhppuppsdgvc6svxu36",
).forEach { (label, address) ->
    item {
        SettingRow(
            title = label,
            subtitle = address,
            onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Cadence $label address", address))
                Toast.makeText(context, "$label address copied", Toast.LENGTH_SHORT).show()
            },
        )
    }
}
```

(`context`, `ClipboardManager`, `ClipData`, `Toast` already imported/available in AboutTab from
the updates work — verify, don't re-import.)

- [ ] **Step 5: Verify build + manual checklist** (device/emulator if available, else state why skipped)

  - Add one server of each available type; bad credentials → inline error, no crash, nothing saved
  - Sync pulls all servers; airplane-mode playback works per server; mode switch filters union
  - Disable one server → its tracks vanish without restart; delete → pruned after sync
  - Plex PIN timeout/cancel → clean return to form
  - Support rows copy exact addresses (compare character-for-character)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/cadence/music/ui/Screens.kt app/src/main/java/com/cadence/music/data/LibraryRepository.kt
git commit -m "feat: server list ui with type picker and auth"
```

---

### Task 6: Final verification sweep

**Files:** none (verification only)

- [ ] **Step 1: Full build + tests**

Run: `sh gradlew :app:assembleDebug :app:testDebugUnitTest 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Test totals ≥ 50** (41 existing + ≥9 new: 3 model + ≥3 emby + 3 plex)

Run the XML counter over `app/build/test-results/testDebugUnitTest/*.xml`.

- [ ] **Step 3: Legacy-compat sweep** — no caller of the deleted `prefs.server`, no direct
  `library.subsonic`/`ArtResolver(subsonic)` construction, no un-namespaced `"sub:"` assumptions
  outside source classes:

Run: `rg -n "prefs\.server\b|library\.subsonic|ArtResolver\(subsonic|syncServer\(\)" app/src/main --no-heading`
Expected: zero hits.

- [ ] **Step 4: Spec cross-check** — typed list + migration (T1), shared core + both E-sources
  (T2), Plex + PIN (T3), fan-out + routing + namespacing + v9 (T4), list UI + picker + auth
  forms + support buttons (T5), mode radio unchanged, MusicSource interface unchanged,
  no new deps.

---

## Self-Review

**1. Spec coverage:** typed list + migration → T1+T4(migration); auth per type (incl. PIN) →
T2/T3/T5; shared core + version quirks → T2 (+T6 device note); Plex native → T3/T5; fan-out +
isolation → T4; mode-union + toggles → T4/T5; server list UI + picker + delete-confirm → T5;
mode radio unchanged → T5 (explicit keep); About support → T5; no new deps → all tasks.

**2. Placeholder scan:** No TBD/TODO/"appropriate". The two `TODO("implement per plan")`-looking
moments were caught during writing — Task 2/3 shells carry FULL bodies/endpoint specs inline.
`scope`/`context` reuse notes name their source tasks. Exact SQL, URLs, header shapes included.

**3. Type consistency:** `ServerEntry/ServerType` fields identical T1↔T2/T3/T5. Key format
`<entryId>:<remoteKey>` with `sub:/jelly:/emby:/plex:/local:` remote dialects consistent
T2↔T3↔T4. `sourceId` bare-type rule holds everywhere (no task namespaces it).
`ping/listAlbums/albumTracksByKey/search/streamUrl/downloadUrl/coverArtUrl/setStarred` surface
identical across source tasks. `entryForServerId/remoteKey` signatures fixed in T4. DAO v9
UPDATEs cover tracks/albums/downloads; lyrics keyed by row id (no change); resume handled by
prefix-on-miss (no prefs migration needed).
