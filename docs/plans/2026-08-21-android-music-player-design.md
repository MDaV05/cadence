# Music Player for Android — Design & Phased Plan

## Product statement

A native Android music player: light, fast, efficient. Three usage modes
(API-only / local-only / hybrid), robust downloads, smart shuffle that always
prefers local files, web metadata + artist pages, and a plugin system so the
stock app stays minimal while the community extends it.

## Architecture

| Layer | Choice | Notes |
|---|---|---|
| UI | Kotlin + Jetpack Compose, Material 3 | Minimal custom theme |
| Playback | Media3 / ExoPlayer | Offload enabled, gapless, ReplayGain |
| Core engine | Rust crate via UniFFI (phase-gated) | Scanner, tag parsing, download queue, LRU cache — only after Kotlin profiling proves need |
| Data | Room (SQLite) | Tracks keyed by `(sourceId, serverId, mbid)` |
| Sources | `MusicSource` interface | `LocalSource`, `SubsonicSource`, `CompositeSource` |
| Metadata | MusicBrainz + Cover Art Archive + LRCLIB (+ optional Last.fm w/ user key) | All keyed by MusicBrainz ID |
| Plugins | Phase 4: QuickJS sandbox | Single-file JS mods |

### Core interfaces

```kotlin
interface MusicSource {
    val id: String
    suspend fun search(q: String): List<TrackRef>
    suspend fun albums(artistId: String): List<Album>
    suspend fun tracks(albumId: String): List<Track>
    suspend fun streamUrl(track: Track): String?
    suspend fun download(track: Track): DownloadJob   // no-op for LocalSource
    suspend fun sync(): SyncDelta                      // incremental
}

// Shuffle = one code path. Resolution happens at play-time.
suspend fun resolve(track: Track): Playable =
    localFileFor(track) ?: streamUrl(track)?.let(::Stream)
```

### Data model (essentials)

- `tracks(id, source_id, server_id, mbid, title, artist_id, album_id, path?, duration, replaygain, play_count, last_played)`
- `albums`, `artists` (with `mbid`, bio, image_url, fetched_at)
- `downloads(track_id, status, bytes_done, transcode)` — WorkManager-backed
- `cache_entries(path, track_id, size, last_access)` — LRU stream cache
- `plugins(id, enabled, entry_path, permissions)`

### Key behaviors

- **Shuffle**: uniform over whole library; per-track resolve prefers local file,
  else streams. Streamed audio is written through the LRU cache (default cap
  2–4 GB, configurable) → offline coverage grows organically.
- **Modes**: mode = which sources are active in `CompositeSource`. Switching
  modes never migrates data; downloads stay valid across modes.
- **Sync**: incremental via server last-modified / musicFolderId; full rescan
  only on demand.

---

## Phase 0 — Skeleton (week 1)

- Compose app scaffold, Room schema v1, navigation shell (Library / Search /
  Now Playing / Settings).
- `MusicSource` interface + `LocalSource` reading MediaStore.
- CI: assemble debug APK on every push.

**Done when**: app launches, lists device music, plays a song via Media3.

## Phase 1 — Playback core (weeks 2–3)

- Media3 session: notification controls, queue, gapless, offload.
- Shuffle-all + resolve-at-playtime (local-first). Repeat, queue editing.
- ReplayGain application; 10-band EQ (media3 DSP) behind settings flag.
- Now Playing screen: minimal — art, scrubber, transport, queue sheet.

**Done when**: shuffle across 5k+ local tracks never streams/stalls; gapless
verified between consecutive tracks; media notification + lockscreen controls.

## Phase 2 — Subsonic source + downloads (weeks 3–6)

- `SubsonicSource`: auth (token/salt), ping, scan, search3, album lists,
  stream (with transcode options), coverArt.
- Incremental sync with diffing; background refresh via WorkManager.
- Download manager: queue UI, resumable range requests, transcode-aware
  (opus/128 option), pause/resume/cancel, storage stats.
- LRU stream cache with configurable cap + eviction policy.
- Mode switcher in Settings: API-only / Local-only / Hybrid.

**Done when**: connected to a Navidrome instance with 50k tracks: browse <300ms
after sync, download 100 albums overnight without wake-lock abuse, airplane-mode
playback uses downloaded files, streamed songs replay offline from cache.

## Phase 3 — Metadata & artist pages (weeks 6–8)

- MusicBrainz client (rate-limit compliant, UA string), Cover Art Archive,
  LRCLIB synced lyrics.
- Artist page: image, bio (Wikipedia extract via Wikidata relation), discography
  (albums grouped: studio/live/comp), top tracks.
- Album page enrichment: genres, release dates, lyrics view w/ sync highlight.
- Match pipeline: local tags → MBID where possible, fuzzy fallback
  (`artist + album + year`); manual re-match UI per album.
- Optional Last.fm provider (user-supplied key).

**Done when**: artist pages populate for a typical library ≥90% match rate;
synced lyrics display; all metadata cached in DB (no network on repeat visits).

## Phase 4 — Scrobbling, polish, hardening (weeks 8–10)

- ListenBrainz + Last.fm scrobbling (batched, offline queue).
- Android Auto + Wear OS basics (Media3 gives most of it).
- Widgets (compact player, 4×2).
- Battery/network pass: batch syncs, image disk cache eviction, Doze-safe jobs.
- Rust evaluation gate: profile scanner/tag-parse/sync on 50k library. Port to
  Rust via UniFFI **only** the hot paths that justify it (expected: scanner +
  tag parser). If Kotlin meets targets, skip Rust entirely.

**Done when**: battery drain comparable to Symfonium in 24h test; cold start
<800ms; scanner handles 50k tracks <60s.

## Phase 5 — Plugin system (weeks 10+)

- QuickJS runtime, sandboxed: no filesystem/net except injected `http` bridge
  with per-plugin permission prompts.
- Plugin API surface (small, versioned):
  - `registerMetadataProvider(kind, matcher, fetcher)` — ratings, bios, lyrics
  - `registerSource(descriptor)` — new backends (Jellyfin, Plex, …)
  - UI hooks limited to declared slots (album badge, artist tab, now-playing line)
- Distribution: single `.js` file import + simple registry listing.
- First plugins ship with the SDK as reference: **AOTY ratings scraper**
  (community-maintained, expected to break — by design it can't hurt core),
  Jellyfin source, bandcamp-style metadata provider.

**Done when**: AOTY plugin adds critic/user scores to album pages without any
core-app knowledge of AOTY; a broken plugin degrades gracefully (disabled +
toast), never crashes the app.

## Explicitly deferred

- Plex/Jellyfin sources (plugin candidates, post-v1)
- Chromecast / session sharing
- Cloud sync of app state
- iOS

## Risks

| Risk | Mitigation |
|---|---|
| Scope creep in metadata matching | Ship fuzzy-match MVP; manual rematch UI before perfectionism |
| AOTY/legal gray area | Plugin-only, community-distributed, no bundled scraper |
| Rust time sink | Phase-gated behind profiling evidence |
| Subsonic API variance across servers | Test matrix: Navidrome, Gonic, Airsonic-Advanced |
