# Changelog

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
