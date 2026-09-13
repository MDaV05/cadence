# Changelog

## 0.15.4

- **Curated Skin Packs & Theme Studio**: Choose from 5 distinct visual experiences (Iris, Spotify, Apple Music, Analog Turntable, and Studio) complete with custom typography (Outfit geometric font), custom corner radius shaping, and live theme preview cards in Settings.
- **Authentic Apple Music Experience**: Complete 1:1 Apple Music visual fidelity across primary app surfaces:
  - Dynamic spring artwork scaling (1.0 playing / 0.84 paused with animated elevation shadow)
  - Centered iOS drag grabber pill and overflow menu
  - Left-aligned bold track title + artist with Cupertino Red star favorite toggle
  - Live scrubber with negative remaining time display (`-X:XX`)
  - Cupertino transport glyphs and in-player hardware volume slider (`AudioManager.STREAM_MUSIC`)
  - Bottom utility bar (lyrics quote, AirPlay/output, queue)
  - Floating squircle mini-player with Cupertino Red progress bar
  - Frosted dark navigation bar with "Listen Now" tab and Cupertino Red active tint
  - "Listen Now" home screen header with uppercase date tracking label and pill shuffle button
- **Authentic Spotify Experience**: Full 1:1 Spotify layout across Now Playing (heart button, green scrubber & play circle, rounded cover art), Mini Player, and bottom navigation bar.
- **Analog Turntable Experience**: Dedicated turntable vinyl player layout with spinning disc animation, tone arm aesthetic, and dedicated controls.
- **Playback Polish**: Fixed replay when tapping Play at the end of a track (`STATE_ENDED`), seamlessly rewinding to start.

## 0.15.3

- **Artist pictures fixed**: Your custom artist photo now shows everywhere — Library grid, Stats Top Artists, and the artist page — instead of only on the artist page. It also updates live when changed.
- **Correct artist bios and photos**: Artist bios and pictures now resolve through MusicBrainz, which links every artist to its exact Wikipedia article. No more rockets, proteins, or Greek letters standing in for musicians — unknown artists get a clean initials placeholder instead of a wrong page. Old wrong entries are wiped once on update.
- **Attribution**: Artist pages now show a "Photo & bio via Wikipedia" link to the source article.

## 0.15.2

- **Playlists moved into Library**: The Library screen now has a fourth Playlists tab (browser, create, rename, delete), keeping everything musical in one place.
- **Stats screen on the bottom bar**: A full listening-stats page replaces the old Playlists shortcut — a big hero with hours listened, plays and unique tracks, week plays and streak, Top Songs with cover art (tap to play), Top Artists with photos, Top Genres, and ListenBrainz totals when a token is set.
- **Liked Songs**: A pinned card at the top of the Playlists tab, live-counting every liked track. Like is now available on every song, including local files, Telegram and Plex — and for Navidrome/Subsonic/Jellyfin/Emby tracks it syncs with the server's own star, both ways.
- **UI audit fixes**: Settings no longer renders under the status bar; the lyrics editor can no longer wipe lyrics when saving mid-load; the no-op Lyrics button on Now Playing opens full lyrics; tab positions survive back-navigation; a bigger seek-bar touch target; Now Playing scrolls on short screens; lyrics overlay respects system bars; lists no longer carry double bottom padding; plus smaller polish (ellipsis truncation, empty states, pluralization).

## 0.15.1

Security-focused release from a full third-party-style audit (14 confirmed findings, all fixed and re-verified):

- **ListenBrainz scrobbling actually works**: submissions were being rejected by the API (missing `listened_at`) and the retry queue replayed forever; the queue is now capped, paced, and stops cleanly on permanent rejections. Replayed listens keep their original timestamps.
- **DoS & crash hardening**: hostile or oversized artist tags from a music server can no longer hang the app in name-parsing regexes (length-capped), crash the long-press sheet (bounded candidate gate), or produce uncaught JSON crashes from cover-art and MusicBrainz lookups.
- **Download integrity & storage**: downloads write atomically via a temp file with a free-space precheck and a real content-length verification — truncated streams are no longer marked "done", a 416 no longer wedges a track permanently, and deleting a failed download sweeps its orphaned bytes.
- **Credential leak prevention**: redirects in media-server and metadata HTTP clients are followed only same-origin (server tokens and passwords can no longer ride a 3xx to another host), API-key URLs and ListenBrainz tokens stay out of URLs and logs, GitHub update downloads must be https on github.com hosts, and server URLs are restricted to http(s) at save and on read.
- **Cleartext downgrade guard**: playback failover between primary/secondary URLs no longer silently adopts an http URL when an https one was active.
- **Media session trust is now UID-based** instead of a client-claimed package name; the TDLib dependency AAR is checksum-pinned in CI; app data (play history, audio, covers) is excluded from cloud/device-transfer backups.

## 0.15.0

- **Artist Pictures Grid**: The Artists tab is now a grid of circular artist photos (with an initials fallback when no image is cached) instead of a plain name list.
- **One-Tap Library Download**: A single action in Downloads and Settings → Storage downloads every not-yet-offline track across your active servers, with a confirm dialog that shows the count and warns on mobile data.
- **Downloads as Albums**: Offline content is grouped into album cards (even partially downloaded ones) with an "x/y offline" count; a Failed section keeps retry within reach.
- **Live Download Progress**: Song rows and the album/playlist download buttons show an inline spinner while a track is downloading.
- **Now Playing Hierarchy**: Title, album, and artist are shown in that order, and both the album and artist lines jump to their pages.
- **Go-To Actions**: Long-pressing a song links to its album and artist — including featuring artists, shown only when that artist actually has a page in the library.
- **Bigger Cache Limits**: Stream and image caches now range 1–100 GB or fully unlimited.

## 0.14.4

- Wikipedia Artist Bio & Photo Integration: Fixed URL path encoding issue where multi-word artist names produced HTTP 404s.
- Music-Aware Disambiguation: Added direct-first article matching and intelligent resolution for artists with ambiguous names (e.g. Nirvana, Justice, Drake, Queen) using qualifier matching and OpenSearch.
- Wikimedia Commons Image Loading: Configured Coil with custom User-Agent to resolve HTTP 403 Forbidden errors when loading Wikipedia artist photos.
- Stale Cache Auto-Cleanup: Automatically purges previously failed/empty artist info cache entries on startup and background sync.

## 0.14.3

- Library Sync & Featured Artist Normalization: Tracks with featured artists (`feat.`, `ft.`, `featuring`, `with`, `w/`, `[feat. ...]`, `(feat. ...)`) and collaborations (`&`, `,`, `/`, `;`, `x`, `vs.`, `and`) cleanly fold under the primary artist instead of creating separate artist entries.
- Unified Album Grouping: Tracks across an album share a single consistent album key, preventing albums with featured or collaborating artists from splitting into duplicate album cards.
- Automatic Database Migration: Re-normalizes all existing tracks and albums in Room database on update without requiring a manual rescan.
- Preserved Band Ensembles: Curated catalog of genuine band ensembles containing `&`, `,`, `+`, `/` (e.g. Earth, Wind & Fire, Simon & Garfunkel, AC/DC, Crosby, Stills, Nash & Young, Hall & Oates) are preserved intact.

## 0.14.2

- Stream Tag Server Attribution & Nicknames: Track subtitles display the active server type (e.g. `Stream (jellyfin)`, `Stream (telegram)`). Added option to set custom server nicknames and toggle custom attribution (e.g. `Stream (dav/jellyfin)`).
- Telegram Artwork Integration: Embedded cover art and chat photos from Telegram are automatically extracted, cached, and rendered across all screens (library, now playing, albums, artists).
- Server Edit Action: Added dedicated edit button to server rows in Settings -> Servers alongside delete, preserving existing authentication tokens.
- Secondary URL with Seamless Playback Failover: Added secondary URL option for Subsonic, Jellyfin, Emby, and Plex servers. Playback automatically tries the primary URL first and transparently falls back to the secondary URL if unreachable.

## 0.14.1

- Telegram Music Source: Connect Telegram chats, channels, groups, bots, or Saved Messages directly to Cadence.
- Interactive Chat Picker: Multi-select exactly which chats and channels to integrate with search filtering, chat type badges, and select all/clear controls.
- Zero-Disk Progressive Streaming: Stream audio directly from Telegram cloud storage via an internal localhost streaming proxy with partial content / HTTP Range request support, cached in Media3's transient LRU cache with zero permanent disk footprint.
- Discrete Album Grouping: Each selected chat or channel cleanly maps to its own album in the library with on-demand track fetching.
- Offline Downloads: Download individual Telegram songs on demand for offline playback using the unified download manager.
- Modular Plugin Architecture: Unified `MusicSource` interface cleanly decoupling Subsonic, Jellyfin, Emby, Plex, and Telegram sources.
- Lightweight Native Packaging: Filtered to mobile ARM architectures and enabled compressed native packaging, shrinking the release APK from 90MB back down to 21MB.
- Safe dynamic TDLib loading with automated Gradle dependency caching.

## 0.14.0

- New versions pop up right when you open the app — changelog included, with Download and Install buttons.
- Artist names keep their punctuation: "Simon & Garfunkel", "Earth, Wind & Fire" and friends are no longer chopped at the &.
- Artist pages no longer crash on names containing %, and names with + resolve correctly.
- Fixed the artist header rendering the name one letter per line.
- Jellyfin/Emby/Plex syncs pick up new, changed, and deleted tracks inside albums you already have (they were silently skipped).

## 0.13.4

- Long-press any song to edit its tags or delete it; album and artist names, covers, bios, and lyrics are editable too.
- Featured artists fold into the main artist and duplicate albums merge into one.
- Server sync finishes in one tap with live progress.
- Artist pictures and bios actually load now.

## 0.13.3

- Long-press works on album songs too, and album/artist names can be renamed from their headers.
- New versions now pop up a notification that jumps straight to About and downloads in one tap.

## 0.13.2

- Featured artists collapse into the first-mentioned artist, and album variants (deluxe, case differences) merge into one entry.
- Server sync now finishes in one tap with a progress indicator instead of needing repeated rescans.
- Long-press a local song to edit its file tags or delete it from the device.
- Artist pictures and bios load reliably, including for previously-missing artists.
