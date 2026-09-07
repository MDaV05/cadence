# Cadence

A light, fast, native Android music player for your own library — freedom of sources for your music library.

[![GitHub release](https://img.shields.io/github/v/release/MDaV05/cadence)](https://github.com/MDaV05/cadence/releases) [![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

## Features

- **Multi-source streaming** — local files, Subsonic (Navidrome, Gonic, Airsonic…), Jellyfin, Emby, Plex, and Telegram
- **Three library modes** — local files only, remote servers only, or unified hybrid
- **Unified library** — sync your songs under their album and artist, even if they're from multiple sources
- **Offline first** — per-track/album/playlist downloads, plus an LRU stream cache so streamed songs replay without network
- **Downloaded tracks join the local set** — marked "Downloaded", included in local browsing, shuffle, and search
- **Full queue control** — play next, add to queue, drag-reorder, repeat, shuffle
- **Sound tuning** — ReplayGain normalization, 5-band equalizer + bass boost, gapless playback
- **Rich metadata** — synced lyrics (LRCLIB), artist bios and images (Wikipedia), MusicBrainz-backed art
- **Editable metadata** — change or add song, album, or artist metadata, from names to pictures
- **Scrobbling** — ListenBrainz with offline queue
- **Personal** — playlists with custom covers, themes, sleep timer, widget, Android Auto

## Quick Start

1. Grab the latest `cadence-<version>-release.apk` from the [releases page](https://github.com/MDaV05/cadence/releases) and install it.
2. Open the app:
   - **Local music** — grant audio access in Library and you're done.
   - **Server music** — Settings → Servers → add your Subsonic, Jellyfin, Emby, Plex, or Telegram account → Save & sync.
3. Pick a library mode in Settings → Servers (Local / Server / Local + server).

## Build From Source

Requirements: JDK 17, Android SDK (compileSdk 36, minSdk 26).

```bash
git clone https://github.com/MDaV05/cadence.git
cd cadence
./gradlew assembleDebug
# APK lands in app/build/outputs/apk/debug/
```

Tests: `./gradlew :app:testDebugUnitTest`

## Contributing

If you think a feature is needed, get to work! 
1. Issues and pull requests are welcome — [issue tracker](https://github.com/MDaV05/cadence/issues/new). Lowercase conventional messages (`feat:`, `fix:`, `ui:`, `data:`, `chore:`), matching existing history. By contributing, you agree your work lands under the project's license below.
2. Create your own fork and change the app to your liking!
   
## License

[GNU General Public License v3.0](LICENSE) — free to use, share, and modify; derivatives must stay open under the same terms.

## Support

Questions or bug reports: [open an issue](https://github.com/MDaV05/cadence/issues/new).  
The About tab → "Copy debug info" produces a paste-ready, secret-free summary to attach.  
For direct communications, reach me through [Telegram](https://t.me/MDaV05).

## Donate

If Cadence is useful to you, crypto donations are welcome:

- **BNB Smart Chain:** `0x57Ff65FB4b773F15BdfB507086facd28d8D7d049`
- **Bitcoin:** `bc1qeepyu36y79jw0nn4fyrkhppuppsdgvc6svxu36`
