# Specification: Universal Search & Interactive Source Switcher

**Date:** 2026-10-23  
**Status:** In Review  
**Subsystems:** UI (`LibraryScreen`, `SearchScreen`), Data Layer (`LibraryRepository`, `TrackQueries`), Online Streaming Pipeline (`source/online`)

---

## 1. Executive Summary

This specification outlines the architecture and user experience for two key features in Cadence:
1. **Interactive Source Switcher in Library**: Replaces the static header "Offline" toggle with an animated source filter bar right beneath the main tabs (`Songs`, `Albums`, `Artists`, `Playlists`). Users can toggle `Local` and individual servers on/off, with long-press options on server chips to filter between "All" and "Downloaded Only".
2. **Universal Online Search (YouTube Music & SoundCloud)**: Adds an **Online** tab to the Search screen. Users can search free online sources via lightweight, keyless InnerTube and SoundCloud endpoints, stream tracks directly with Media3, add online tracks to playlists or Liked Songs, and download them for offline listening.

---

## 2. Interactive Source Switcher & Server Filter Bar

### 2.1 UI/UX Behavior in `LibraryScreen`
- **Header Simplification**: Remove the top `FilterChip` ("Offline") next to the "Library" title.
- **Source Filter Bar Placement**: Placed directly under the `ScrollableTabRow` with smooth horizontal scrolling (`LazyRow` with `spacedBy(8.dp)` and `PaddingValues(horizontal = 16.dp)`).
- **Chips**:
  - `[ 📱 Local ]`: Represents on-device files scanned from the filesystem.
  - `[ ☁️ {ServerName} ]`: One chip for each configured server in `prefs.servers`.
- **States & Animations**:
  - **Active / Inactive**: Active chips display with `primaryContainer` / `primary` colors and an active indicator icon. Inactive chips display with muted `surfaceVariant` styling.
  - **Animated Transitions**: Color changes transition via `animateColorAsState` (250ms tween). Press micro-interaction scales to `0.96f` on tap.
  - **Last Active Guard**: If only one source is currently active, tapping it does not toggle it off (prevents an empty, unnavigable library state).
- **Server Offline Sub-State (Design Idea 2)**:
  - Long-pressing a server chip (or clicking its options badge) presents a dropdown menu:
    - **All Content** (streaming + downloaded)
    - **Downloaded Only** (hides cloud-only songs for this server)
  - When in "Downloaded Only" mode, the chip updates its label and icon: `☁️ {ServerName} (Offline)` with a subtle downloaded badge.

### 2.2 Data Layer & SQL Integration
- **Preferences (`Prefs.kt`)**:
  - `activeSourceIds: Set<String>`: Set of enabled source IDs (e.g. `{"local", "subsonic-1"}`). Defaults to all configured sources.
  - `offlineOnlyServerIds: Set<String>`: Set of server IDs restricted to downloaded-only files.
- **Repository Filtering (`LibraryRepository.kt` & `TrackQueries.kt`)**:
  - `observeLibraryFilter()`: Emits active sources and server offline restrictions.
  - Queries for `tracksPaged()`, `albumGroups()`, and `artistTiles()` dynamically append:
    - `sourceId IN (:activeSources)`
    - For servers in `offlineOnlyServerIds`: `AND (path IS NOT NULL AND path != '')`

---

## 3. Universal Online Search & Streaming Pipeline

### 3.1 UX Architecture in `SearchScreen`
- **Tabs**: Two primary tabs at the top of the search view:
  - `[ In Library ]`: Instant, local Room database queries (existing behavior).
  - `[ Online ]`: Remote network queries to free music streaming providers.
- **Online Filter Chips**:
  - `[ All ]`, `[ YouTube Music ]`, `[ SoundCloud ]` to quickly narrow remote results.
- **Result Presentation**:
  - Results rendered with title, artist, album/uploader, duration, cover art, and a distinct provider badge (`YT Music` / `SoundCloud`).
  - Search history tracks both library and online queries.

### 3.2 Online Source Architecture
- **Interface: `OnlineSource`**:
  ```kotlin
  interface OnlineSource {
      val sourceId: String
      val displayName: String
      suspend fun search(query: String): List<Track>
      suspend fun resolveStream(serverTrackId: String): StreamResolution
  }

  data class StreamResolution(
      val streamUrl: String,
      val mimeType: String,
      val durationMs: Long?,
      val bitrate: Int? = null,
  )
  ```
- **Lightweight Providers**:
  1. **`YouTubeMusicSource`**:
     - Keyless OkHttp requests to YouTube InnerTube web client (`https://music.youtube.com/youtubei/v1/search`).
     - Extracts search results (title, artist, videoId, thumbnail, duration).
     - Resolves stream via `youtubei/v1/player` picking the highest-bitrate audio/webm or audio/mp4 Opus/AAC stream URL.
  2. **`SoundCloudSource`**:
     - Keyless OkHttp requests to SoundCloud v2 public client API (`https://api-v2.soundcloud.com/search/tracks`).
     - Extracts title, user/artist, permalink, duration, artwork URL.
     - Resolves stream via SoundCloud progressive or HLS audio URL.

### 3.3 Playback, Playlists & Offline Downloads
- **Playback**:
  - Tapping an online song calls `player.playNow(listOf(track))`.
  - Media3 resolves the stream URL through `OnlineStreamResolver` and plays seamlessly.
- **Favoriting & Playlists**:
  - Tapping the heart icon or "Add to playlist" persists the track into the `tracks` database with `sourceId = "ytmusic"` or `sourceId = "soundcloud"`.
  - Seamlessly appears in Cadence playlists and Liked Songs.
- **Offline Download**:
  - Tapping "Download" inserts a `DownloadEntity` and schedules `DownloadWorker`.
  - `DownloadWorker` downloads the resolved audio stream directly into Cadence's offline music directory with ID3 tagging.

---

## 4. Testing & Verification

1. **Unit Tests**:
   - `SourceFilterTest`: Test active source state transitions, "last active" guard, and "downloaded only" filtering.
   - `OnlineSourceTest`: Test parsing of YouTube Music InnerTube responses and SoundCloud track search payloads.
   - `SQLQueryBuilderTest`: Verify SQL generated when sources and offline filters are toggled.
2. **Device / Emulator Verification**:
   - Verify UI layout and animations on running Android emulator (`cadence` AVD).
   - Test search switching, source toggling, and audio stream playback.
