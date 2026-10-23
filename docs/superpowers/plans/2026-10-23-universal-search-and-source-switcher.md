# Universal Search & Interactive Source Switcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement an interactive library source filter bar with long-press offline filtering, and universal online search with YouTube Music & SoundCloud streaming, playlist support, and offline downloads.

**Architecture:**
- **Source Switcher**: Preferences in `Prefs.kt` (`activeSourceIds`, `serverOfflineOnlyIds`) are observed by `LibraryRepository.kt` to dynamically filter SQL queries across `tracksPaged`, `albumGroups`, and `artistTiles`. Compose UI in `LibraryScreen.kt` replaces the static offline chip with animated pills and long-press menus.
- **Universal Online Search**: Modular `OnlineSource` interface with `YouTubeMusicSource` (InnerTube API) and `SoundCloudSource` (v2 API). `SearchScreen.kt` provides "In Library" vs "Online" tabs with source filter chips (`All`, `YT Music`, `SoundCloud`). Stream URLs resolve through `LibraryRepository.streamUrlFor()` for playback, downloads, and playlists.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, AndroidX Room, Media3 / ExoPlayer, OkHttp3, Kotlin Coroutines & Flow.

**Spec:** `docs/superpowers/specs/2026-10-23-universal-search-and-source-switcher-design.md`

## Global Constraints
- Android SDK: compileSdk 35, minSdk 26.
- Pure Kotlin / OkHttp keyless calls for YouTube Music & SoundCloud (no heavy external extractor binaries).
- Never allow all sources to be disabled in the library filter (guard minimum 1 active source).
- Strict backward compatibility with existing Room DB and WorkManager download workers.
- Run tests via `./gradlew testFdroidDebugUnitTest` to verify correctness.

---

### Task 1: Source Filter Data Layer & Preferences

**Files:**
- Modify: `app/src/main/java/com/cadence/music/data/prefs/Prefs.kt`
- Modify: `app/src/main/java/com/cadence/music/data/db/TrackQueries.kt`
- Modify: `app/src/main/java/com/cadence/music/data/LibraryRepository.kt`
- Test: `app/src/test/java/com/cadence/music/data/SourceFilterTest.kt`

**Interfaces:**
- Produces:
  - `Prefs.libraryActiveSources: Set<String>` & `Prefs.observeLibraryActiveSources(): Flow<Set<String>>`
  - `Prefs.serverOfflineOnlyIds: Set<String>` & `Prefs.observeServerOfflineOnlyIds(): Flow<Set<String>>`
  - `LibraryFilter(val activeSources: Set<String>, val offlineServerIds: Set<String>)`
  - `TrackQueries.activeSourceFilter(activeSources: Set<String>, offlineServerIds: Set<String>): Pair<String, Array<String>>`

- [ ] **Step 1: Write the failing unit test for SourceFilter**

Create `app/src/test/java/com/cadence/music/data/SourceFilterTest.kt`:
```kotlin
package com.cadence.music.data

import com.cadence.music.data.db.TrackQueries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFilterTest {

    @Test
    fun `active source filter with all sources returns empty where clause`() {
        val (sql, args) = TrackQueries.activeSourceFilter(
            activeSources = emptySet(),
            offlineServerIds = emptySet(),
        )
        assertEquals("", sql)
        assertTrue(args.isEmpty())
    }

    @Test
    fun `active source filter excludes unselected servers and local`() {
        val (sql, args) = TrackQueries.activeSourceFilter(
            activeSources = setOf("local", "srv1"),
            offlineServerIds = emptySet(),
        )
        assertTrue(sql.contains("sourceId = 'local'"))
        assertTrue(sql.contains("serverId LIKE ?"))
        assertEquals(1, args.size)
        assertEquals("srv1:%", args[0])
    }

    @Test
    fun `active source filter restricts specified servers to offline only`() {
        val (sql, args) = TrackQueries.activeSourceFilter(
            activeSources = setOf("srv1"),
            offlineServerIds = setOf("srv1"),
        )
        assertTrue(sql.contains("(path IS NOT NULL AND path != '')"))
        assertEquals(1, args.size)
        assertEquals("srv1:%", args[0])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testFdroidDebugUnitTest --tests com.cadence.music.data.SourceFilterTest`
Expected: FAIL (unresolved reference `activeSourceFilter`).

- [ ] **Step 3: Implement `Prefs` methods and `TrackQueries.activeSourceFilter`**

In `app/src/main/java/com/cadence/music/data/prefs/Prefs.kt`, add:
```kotlin
    var libraryActiveSources: Set<String>
        get() = sp.getStringSet("lib_active_sources", null) ?: emptySet()
        set(value) = sp.edit().putStringSet("lib_active_sources", value).apply()

    fun observeLibraryActiveSources(): Flow<Set<String>> = callbackFlow {
        trySend(libraryActiveSources)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "lib_active_sources") trySend(libraryActiveSources)
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var serverOfflineOnlyIds: Set<String>
        get() = sp.getStringSet("server_offline_only_ids", null) ?: emptySet()
        set(value) = sp.edit().putStringSet("server_offline_only_ids", value).apply()

    fun observeServerOfflineOnlyIds(): Flow<Set<String>> = callbackFlow {
        trySend(serverOfflineOnlyIds)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "server_offline_only_ids") trySend(serverOfflineOnlyIds)
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }
```

In `app/src/main/java/com/cadence/music/data/db/TrackQueries.kt`, implement `activeSourceFilter`:
```kotlin
    fun activeSourceFilter(
        activeSources: Set<String>,
        offlineServerIds: Set<String>,
    ): Pair<String, Array<String>> {
        if (activeSources.isEmpty() && offlineServerIds.isEmpty()) return "" to emptyArray()

        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (activeSources.isNotEmpty()) {
            val sourceParts = mutableListOf<String>()
            if ("local" in activeSources) {
                sourceParts.add("sourceId = 'local'")
            }
            val serverSources = activeSources.filter { it != "local" }.sorted()
            for (srv in serverSources) {
                if (srv in offlineServerIds) {
                    sourceParts.add("(serverId LIKE ? AND (path IS NOT NULL AND path != ''))")
                } else {
                    sourceParts.add("(serverId LIKE ?)")
                }
                args.add("$srv:%")
            }
            if (sourceParts.isNotEmpty()) {
                clauses.add("(${sourceParts.joinToString(" OR ")})")
            }
        } else if (offlineServerIds.isNotEmpty()) {
            val offlineParts = mutableListOf<String>()
            for (srv in offlineServerIds.sorted()) {
                offlineParts.add("(serverId LIKE ? AND (path IS NOT NULL AND path != ''))")
                args.add("$srv:%")
            }
            clauses.add("(${offlineParts.joinToString(" OR ")})")
        }

        if (clauses.isEmpty()) return "" to emptyArray()
        return " AND " + clauses.joinToString(" AND ") to args.toTypedArray()
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testFdroidDebugUnitTest --tests com.cadence.music.data.SourceFilterTest`
Expected: PASS.

- [ ] **Step 5: Commit changes**

```bash
git add app/src/main/java/com/cadence/music/data/prefs/Prefs.kt \
        app/src/main/java/com/cadence/music/data/db/TrackQueries.kt \
        app/src/test/java/com/cadence/music/data/SourceFilterTest.kt
git commit -m "feat(data): add source and offline filter queries and preferences"
```

---

### Task 2: Interactive Source Switcher UI in `LibraryScreen.kt`

**Files:**
- Modify: `app/src/main/java/com/cadence/music/ui/LibraryScreen.kt`
- Modify: `app/src/main/java/com/cadence/music/data/LibraryRepository.kt`

**Interfaces:**
- Consumes:
  - `container.prefs.observeLibraryActiveSources()`
  - `container.prefs.observeServerOfflineOnlyIds()`
  - `container.prefs.servers`
- Produces:
  - `SourceFilterBar` Composable with animated chips and long-press sub-menus.

- [ ] **Step 1: Wire filter state in `LibraryRepository.kt`**

Update `observeModeAndActive` in `LibraryRepository.kt` to integrate `libraryActiveSources` and `serverOfflineOnlyIds` into queries so changing filters re-queries the library immediately.

- [ ] **Step 2: Create `SourceFilterBar` Composable in `LibraryScreen.kt`**

Add `SourceFilterBar` in `app/src/main/java/com/cadence/music/ui/LibraryScreen.kt`:
- Placed directly under `ScrollableTabRow`.
- Horizontal scroll with `LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp))`.
- Includes `[ 📱 Local ]` chip and `[ ☁️ {ServerName} ]` chips.
- Animated selection with `animateColorAsState` and tactile scaling.
- Minimum 1 source enforced: tapping the last active source does not toggle it off.
- Long-press / dropdown menu on server chips allowing toggle of "All Content" vs "Downloaded Only".
- Delete old header "Offline" filter chip next to the "Library" title.

- [ ] **Step 3: Verify build and unit tests**

Run: `./gradlew testFdroidDebugUnitTest`
Expected: PASS.

- [ ] **Step 4: Commit changes**

```bash
git add app/src/main/java/com/cadence/music/ui/LibraryScreen.kt \
        app/src/main/java/com/cadence/music/data/LibraryRepository.kt
git commit -m "feat(ui): add animated source filter bar with per-server offline toggle"
```

---

### Task 3: Online Sources Infrastructure (`YouTubeMusicSource` & `SoundCloudSource`)

**Files:**
- Create: `app/src/main/java/com/cadence/music/data/source/online/OnlineSource.kt`
- Create: `app/src/main/java/com/cadence/music/data/source/online/YouTubeMusicSource.kt`
- Create: `app/src/main/java/com/cadence/music/data/source/online/SoundCloudSource.kt`
- Test: `app/src/test/java/com/cadence/music/data/source/online/OnlineSourceTest.kt`

**Interfaces:**
- Produces:
  - `interface OnlineSource { val sourceId: String; suspend fun search(q: String): List<Track>; suspend fun resolveStream(key: String): String? }`
  - `class YouTubeMusicSource(client: OkHttpClient): OnlineSource`
  - `class SoundCloudSource(client: OkHttpClient): OnlineSource`

- [ ] **Step 1: Write test for OnlineSource response parsing**

Create `app/src/test/java/com/cadence/music/data/source/online/OnlineSourceTest.kt`:
```kotlin
package com.cadence.music.data.source.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OnlineSourceTest {

    @Test
    fun `parse YouTube music search response extracts tracks`() {
        val json = """
        {
          "contents": {
            "tabbedSearchResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [{
                        "musicShelfRenderer": {
                          "contents": [{
                            "musicResponsiveListItemRenderer": {
                              "flexColumns": [
                                {
                                  "musicResponsiveListItemFlexColumnRenderer": {
                                    "text": { "runs": [{ "text": "Test Song" }] }
                                  }
                                },
                                {
                                  "musicResponsiveListItemFlexColumnRenderer": {
                                    "text": { "runs": [{ "text": "Test Artist" }, { "text": " • " }, { "text": "Test Album" }] }
                                  }
                                }
                              ],
                              "playlistItemData": { "videoId": "abc123vid" }
                            }
                          }]
                        }
                      }]
                    }
                  }
                }
              }]
            }
          }
        }
        """.trimIndent()

        val parsed = YouTubeMusicSource.parseSearchResults(json)
        assertEquals(1, parsed.size)
        assertEquals("Test Song", parsed[0].title)
        assertEquals("Test Artist", parsed[0].artist)
        assertEquals("abc123vid", parsed[0].key)
    }

    @Test
    fun `parse SoundCloud search response extracts tracks`() {
        val json = """
        {
          "collection": [
            {
              "id": 987654,
              "title": "SC Track Title",
              "user": { "username": "SC Artist" },
              "duration": 210000,
              "artwork_url": "https://example.com/art.jpg",
              "media": {
                "transcodings": [
                  {
                    "url": "https://api-v2.soundcloud.com/media/stream",
                    "format": { "protocol": "progressive", "mime_type": "audio/mpeg" }
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val parsed = SoundCloudSource.parseSearchResults(json)
        assertEquals(1, parsed.size)
        assertEquals("SC Track Title", parsed[0].title)
        assertEquals("SC Artist", parsed[0].artist)
        assertEquals("987654", parsed[0].key)
        assertEquals(210000L, parsed[0].durationMs)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testFdroidDebugUnitTest --tests com.cadence.music.data.source.online.OnlineSourceTest`
Expected: FAIL (unresolved classes).

- [ ] **Step 3: Implement `OnlineSource`, `YouTubeMusicSource`, and `SoundCloudSource`**

Create `OnlineSource.kt`, `YouTubeMusicSource.kt`, and `SoundCloudSource.kt` using pure OkHttp with JSON parsing to extract search hits and resolve audio streams.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testFdroidDebugUnitTest --tests com.cadence.music.data.source.online.OnlineSourceTest`
Expected: PASS.

- [ ] **Step 5: Commit changes**

```bash
git add app/src/main/java/com/cadence/music/data/source/online/ \
        app/src/test/java/com/cadence/music/data/source/online/
git commit -m "feat(source): implement keyless YouTube Music and SoundCloud online sources"
```

---

### Task 4: Online Search UI & Audio Playback Integration

**Files:**
- Modify: `app/src/main/java/com/cadence/music/ui/SearchScreen.kt`
- Modify: `app/src/main/java/com/cadence/music/CadenceApp.kt`
- Modify: `app/src/main/java/com/cadence/music/data/LibraryRepository.kt`

**Interfaces:**
- Consumes:
  - `YouTubeMusicSource` and `SoundCloudSource` via `AppContainer`
  - `PlayerConnection.playNow()`
- Produces:
  - Tabbed Search UI: `[ In Library ]` and `[ Online ]`
  - Online results list with source tags (`YT Music`, `SoundCloud`), instant playback, add-to-playlist, and download actions.

- [ ] **Step 1: Register online sources in `AppContainer` and `LibraryRepository.streamUrlFor()`**

In `CadenceApp.kt`, instantiate `YouTubeMusicSource` and `SoundCloudSource`.
In `LibraryRepository.streamUrlFor(track)`, add branches for `sourceId == "ytmusic"` and `sourceId == "soundcloud"` to invoke `resolveStream(track.key)`.

- [ ] **Step 2: Update `SearchScreen.kt` with Tabbed Layout**

- Add tab switcher at top: `[ In Library ]` vs `[ Online ]`.
- Under `[ Online ]`, add filter chips: `[ All ]`, `[ YouTube Music ]`, `[ SoundCloud ]`.
- When debounced query is typed in Online tab, launch parallel coroutines to query enabled sources.
- Show results with track row, art preview, source badge, tap-to-play, heart/playlist button, and download button.

- [ ] **Step 3: Run full test suite**

Run: `./gradlew testFdroidDebugUnitTest`
Expected: PASS.

- [ ] **Step 4: Commit changes**

```bash
git add app/src/main/java/com/cadence/music/ui/SearchScreen.kt \
        app/src/main/java/com/cadence/music/CadenceApp.kt \
        app/src/main/java/com/cadence/music/data/LibraryRepository.kt
git commit -m "feat(search): add online search tab with streaming, playlists, and downloads"
```

---

### Task 5: Emulator Deployment & UI Verification

**Files:**
- None (Build & run APK on emulator)

- [ ] **Step 1: Build debug APK and install on running emulator**

Run: `./gradlew installFdroidDebug`
Verify: APK installed successfully on `emulator-5554`.

- [ ] **Step 2: Launch Cadence on emulator**

Run: `/home/dav/Android/Sdk/platform-tools/adb shell am start -n com.cadence.music/com.cadence.music.MainActivity`
Verify: Activity launches into foreground.

- [ ] **Step 3: Take screenshot of Library Screen and Search Screen**

Run: `/home/dav/Android/Sdk/platform-tools/adb exec-out screencap -p > /home/dav/Projects/music/library_source_switcher.png`
Verify UI appearance and layout against design taste standards.
