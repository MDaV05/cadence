# In-App Updates + About Section — Design

## Goal
Cadence checks GitHub Releases for a newer stable version and lets the user download +
install it from inside the app. A new About tab in Settings hosts update options, project
links, credits, and diagnostics. Approach A (GitHub Releases API + system DownloadManager).

## Non-goals
- Silent/background auto-install (impossible for sideloaded apps; the OS always confirms).
- Periodic background checks (no WorkManager; check on cold start + manual only).
- Play Store / other distribution channels.

## Architecture
`data/update/UpdateChecker.kt` — pure, JVM-testable core: fetch latest stable release,
pick the `-release.apk` asset, compare versions. UI glue (About tab rows, cold-start trigger
in `CadenceApp`, DownloadManager enqueue, install intent) stays in existing components.
Zero new dependencies (`HttpURLConnection`, `DownloadManager`, `FileProvider` via core-ktx).

## Components

### 1. Update check (`data/update/UpdateChecker.kt`)
- `GET https://api.github.com/repos/MDaV05/cadence/releases/latest`, 10s connect / 30s read
  timeout, `Accept: application/vnd.github+json`, `User-Agent: Cadence/<version>`.
- Parse: `tag_name`, `prerelease`, `html_url`, `assets[]` (`name`, `browser_download_url`).
- Skip when `prerelease == true`. Asset pick: `cadence-<tag>-release.apk` exact match; no match → no update.
- Version compare: strip leading `v`, split on `.`, compare leading numeric runs pairwise;
  first difference decides; all equal → no update. Unparseable either side → no update (fail safe).
- Result type: `sealed interface UpdateStatus { UpToDate; Available(tag, url, notesUrl); Failed }`
  (`Failed` covers no-network, non-2xx incl. rate-limit 403/429, bad JSON, missing asset).
- Exposed state: `UpdateState` holder (status + last-checked timestamp) fed by cold-start check
  and manual refresh; About rows observe it.

### 2. Triggers & download flow
- Cold start (`CadenceApp.onCreate`, fire-and-forget coroutine): run check iff
  `prefs.updateAutoCheck` (default true). Any network (payload ~2KB). Never blocks startup,
  never dialogs — status text only.
- Manual: About "Check for updates" row → spinner → status text.
- Download: tapping an Available row enqueues `DownloadManager.Request` (title "Cadence <tag>",
  `setNotificationVisibility(VISIBLE_NOTIFY_COMPLETED)`, destination `getExternalFilesDir(DOWNLOADS)`).
  System notification owns progress/completion; tapping it launches the installer. "Release notes"
  row opens `html_url` in the browser (visible only after a check resolves it).
- Prefs addition: `updateAutoCheck: Boolean` (default true) in `Prefs.kt`.

### 3. About tab (`Screens.kt`: 5th tab "About")
Same `LazyColumn` + `SectionHeader`/`SettingRow` pattern as existing tabs.
- *App*: version row (`v<versionName> (<versionCode>)` via PackageManager); update row
  (subtitle = status: Up to date / Available / Failed + last-checked time); auto-check toggle;
  release-notes link (hidden until URL known).
- *Project*: GitHub repo link (`https://github.com/MDaV05/cadence`), Report-an-issue link
  (`.../issues/new`), open-source credits list (names only: Jetpack Compose/Material3, Media3,
  Room, Coil, WorkManager, Paging).
- *Diagnostics*: track count (existing `TrackDao.count()` one-shot), album/artist counts
  (one-shot over existing aggregate flows), downloads + cache bytes on disk
  (`filesDir/downloads`, stream-cache dir, Coil image dir), "Copy debug info" (version,
  counts, library mode, server-configured yes/no — NEVER password/token) via clipboard.
- NOTE (deviation): no license row — repo has no LICENSE file. Add it when a license is chosen.

### 4. Manifest & install path
- `AndroidManifest.xml`: `REQUEST_INSTALL_PACKAGES` + `FileProvider`
  (`androidx.core.content.FileProvider`, authorities `<appId>.files`, `xml/file_paths` with
  `external-files-path name="downloads" path="Download/"`). The provider backs an in-app
  "Tap to install" row for the finished download (in addition to the system notification tap).
  No new dependencies.

## Data flow
Cold start → `UpdateChecker.latest()` → `UpdateStatus` → About subtitle.
Tap Available → `DownloadManager.enqueue` → system notification → installer intent.
All network failures → `Failed` status text; no retries, no dialogs, no crashes.

## Error handling
API/parse/asset failures → "Couldn't check for updates" + last-checked time. Rate limits
(60/hr anonymous) unreachable via 1-per-launch + manual. Install failures surface in the
system installer, not the app. `REQUEST_INSTALL_PACKAGES` denied → system handles the prompt.

## Testing
- JVM JUnit4 (existing style): version-compare matrix (equal/newer/older/v-prefix/unequal
  length/unparseable), asset-pick (exact match, missing, prerelease skip), tag-parse.
- Build green (`assembleDebug`); device pass: airplane-mode check → Failed text; tap update
  row → system download notification → installer; About counts match Library; copy-debug-info
  pastes with no secrets.
