# Changelog

## 0.17.4

- **Cross-Source Library Deduplication**:
  - Songs, search, album, artist, liked, and home-shelf lists now merge the same recording from local storage and remote servers instead of showing duplicates.
  - Local files and downloaded copies are preferred for playback; play counts, favorites, track numbers, and artwork metadata are combined.
  - Library, album, and liked counts now count unique tracks, and starring a merged track updates every source that has it.

## 0.17.3

- **Modern Home Screen Widgets**:
  - **Compact Player Bar (4x1 / 4x2)**: Sleek, responsive bar widget featuring rounded album art, bold track title, artist name, and modern playback controls (previous, play/pause, next).
  - **Large Showcase Player Card (4x2 / 3x3)**: Premium card widget showcasing prominent album cover art, track info, branded Cadence badge, and spacious touch-friendly transport buttons.
  - **Album Art & Artwork Pipeline**: Direct integration with Coil image loader with automatic software bitmap decoding and safe anti-aliased corner rounding, staying safely below Android's RemoteViews Binder memory limits.
  - **One-Tap App Launch**: Tapping the widget body, album art, or title smoothly launches Cadence directly into Now Playing.
  - **Modern Android 12+ (API 31+) Compatibility**: Exported provider declarations, responsive target cell width/height metadata, and immutable pending intents ensuring full compatibility with modern home screen launchers.

## 0.17.2

- **Metadata Architecture Modernization & Hardening**:
  - **Unified `MetadataHttpClient`**: Consolidated external HTTP logic across LRCLIB, Wikipedia, MusicBrainz, and ListenBrainz into a single resilient, memory-safe client.
  - **Memory DOS Protection**: Strict 2MB stream bounding (`readNBytes(2MB)`) enforced on lyrics fetching, eliminating potential out-of-memory risks from malformed or excessive upstream payloads.
  - **RFC 3986 URL Encoding**: Standardized `%20` query and path encoding to resolve intermittent search misses on lyrics and metadata providers.
  - **MusicBrainz Rate Limiting**: Built-in thread-safe rate limiter enforcing the mandatory $\ge 1000\text{ms}$ delay between outbound MusicBrainz API calls, avoiding HTTP 503 IP bans during background metadata syncs.
  - **Persistent Offline Album Art**: Resolved Cover Art Archive URLs are now stored directly in Room (`artPath` on `AlbumEntity`), eliminating duplicate network requests on app restarts and ensuring instant offline cover rendering.
  - **Cleaner Test Suite**: Added deterministic socket-level unit tests for metadata HTTP redirection, bounding, and rate-limiting while purging obsolete live-network tests.

## 0.17.1

- **Interactive Library Source Switcher**:
  - Modern horizontal filter bar right beneath library tabs replaces the legacy static offline button.
  - One-tap toggle for Local files and individual remote servers with smooth animations and haptic feedback.
  - Single-source guard ensures at least one source remains active at all times.
  - Long-press menu on server chips to switch between "All Content (Online + Offline)" and "Downloaded Only" with a badge indicator.
- **Download Hardening & Audio Integrity Sniffing**:
  - Room migration v17 adds persistent `errorText` tracking for download diagnostics.
  - Pre-flight header sniffing (first 4KB) verifies downloaded content is genuine audio, rejecting server HTML/auth error bodies before saving.
  - Exponential backoff retry logic and queue repair mechanisms for interrupted downloads.
- **Search Reversion**:
  - Removed experimental online search (YouTube Music & SoundCloud) to preserve native library search speed and reliability.

## 0.17.0

- **Universal Online Search (YouTube Music & SoundCloud)**:
  - Search beyond your local library across YouTube Music (InnerTube) and SoundCloud concurrently.
  - Direct tap-to-stream playback with instant audio resolution.
  - Save online tracks into custom playlists or download them directly to local disk for offline playback.
  - Quick filter chips to toggle between All sources, YouTube Music, and SoundCloud.
- **Interactive Library Source Switcher**:
  - Modern horizontal filter bar right beneath library tabs replaces the legacy static offline button.
  - One-tap toggle for Local files and individual remote servers with smooth animations and haptic feedback.
  - Single-source guard ensures at least one source remains active at all times.
  - Long-press menu on server chips to switch between "All Content (Online + Offline)" and "Downloaded Only" with a badge indicator.
- **Download Hardening & Audio Integrity Sniffing**:
  - Room migration v17 adds persistent `errorText` tracking for download diagnostics.
