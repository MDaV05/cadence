# Changelog

## 14.0.1

- Telegram Music Source: Connect Telegram chats, channels, or Saved Messages directly to Cadence.
- Zero-Disk Streaming: Stream audio on the fly directly from Telegram cloud storage without permanently downloading files, backed by Media3/ExoPlayer's transient cache.
- Offline Downloads: Download any Telegram song on demand to local storage using the unified download manager.
- Modular Plugin Architecture: Unified `MusicSource` interface cleanly decoupling Subsonic, Jellyfin, Emby, Plex, and Telegram sources.
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
