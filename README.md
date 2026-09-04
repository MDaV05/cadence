# Cadence

A light, fast, native Android music player for your own library — local files, Subsonic servers, or both.

[![GitHub release](https://img.shields.io/github/v/release/MDaV05/cadence)](https://github.com/MDaV05/cadence/releases) [![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

## Features

- **Three library modes** — local files only, Subsonic server only (Navidrome, Gonic, Airsonic…), or hybrid
- **Offline first** — per-track/album/playlist downloads, plus an LRU stream cache so streamed songs replay without network
- **Downloaded tracks join the local set** — marked "Downloaded", included in local browsing, shuffle, and search
- **Full queue control** — play next, add to queue, drag-reorder, repeat, shuffle
- **Sound tuning** — ReplayGain normalization, 5-band equalizer + bass boost, gapless playback
- **Rich metadata** — synced lyrics (LRCLIB), artist bios and images (Wikipedia), MusicBrainz-backed art
- **Scrobbling** — ListenBrainz with offline queue
- **Personal** — playlists with custom covers, themes (incl. custom + Apple Music / Spotify looks), sleep timer, widget, Android Auto
- **Self-updating** — checks GitHub releases on launch, downloads and installs from the About tab

## Quick Start

1. Grab the latest `cadence-<version>-release.apk` from the [releases page](https://github.com/MDaV05/cadence/releases) and install it (Android will confirm the install).
2. Open the app:
   - **Local music** — grant audio access in Library and you're done.
   - **Server music** — Settings → Server → enter your Subsonic URL, username, and password → Save & sync.
3. Pick a library mode in Settings → Server (Local / Server / Local + server).

## Build From Source

Requirements: JDK 17, Android SDK (compileSdk 36, minSdk 26).

```bash
git clone https://github.com/MDaV05/cadence.git
cd cadence
./gradlew assembleDebug
# APK lands in app/build/outputs/apk/debug/
```

Tests: `./gradlew :app:testDebugUnitTest`

Tagged builds (`v*`) name themselves from the tag and bump `versionCode` from commit count; see `.github/workflows/release.yml` for the signed release pipeline.

## Contributing

Issues and pull requests are welcome — [issue tracker](https://github.com/MDaV05/cadence/issues/new). One concern per commit, lowercase conventional messages (`feat:`, `fix:`, `ui:`, `data:`, `chore:`), matching existing history. By contributing, you agree your work lands under the project's license below.

## License

[GNU General Public License v3.0](LICENSE) — free to use, share, and modify; derivatives must stay open under the same terms.

## Support

Questions or bug reports: [open an issue](https://github.com/MDaV05/cadence/issues/new). The About tab → "Copy debug info" produces a paste-ready, secret-free summary to attach.

## Donate

If Cadence is useful to you, crypto donations are welcome:

- **BNB Smart Chain:** `0x57Ff65FB4b773F15BdfB507086facd28d8D7d049`
- **Bitcoin:** `bc1qeepyu36y79jw0nn4fyrkhppuppsdgvc6svxu36`
