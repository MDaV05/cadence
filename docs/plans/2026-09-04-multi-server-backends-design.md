# Multi-Server Backends (Subsonic / Jellyfin / Emby / Plex) — Design

## Goal
Cadence connects to multiple saved servers of four types — Subsonic, Jellyfin, Emby, Plex —
with a type picker at add-time. All backends behave as first-class citizens (sync, downloads,
offline, scrobbling, paged UI) behind the existing `MusicSource` interface, which does not change.

## Non-goals
- Plugin runtime for third-party backends (parked indefinitely; new sources are first-class code).
- Background periodic sync changes (existing triggers only).
- F-Droid flavor (separate follow-up; noted, not specified here).

## Architecture
Typed server configs + per-type native clients. One shared `EmbyLikeClient` core serves
Jellyfin + Emby (same auth shape, same libraries endpoints); standalone `PlexSource` for Plex
(different auth + API). `SubsonicSource` keeps current behavior. Sync fans out across active
API servers with per-server failure isolation. Zero new dependencies.

## Components

### 1. Server data model (`data/prefs`, `data/db` migration)
- `ServerEntry(id, type, url, user, passwordOrNull, tokenOrNull, userIdOrNull, active)` with
  `type ∈ {SUBSONIC, JELLYFIN, EMBY, PLEX}`. Plex stores token + chosen URL, password null.
- Stored as JSON list in SharedPreferences (single-digit counts; no DB table).
- Replaces today's single `ServerConfig`. First launch migrates it to entry id `primary`.
- Track identity namespaced per server instance (`sourceId` gains the entry id, e.g.
  `subsonic:<entryId>`); one-time Room migration re-keys existing `subsonic`/`local` rows.
  Two same-type servers can never collide; play stats stay per namespaced id.

### 2. Auth per type
- Subsonic: URL + user + password, verified with `ping`. Password retained for request signing.
- Jellyfin/Emby: URL + user + password → `POST /Users/AuthenticateByName` → store access token
  + user id, send as `X-Emby-Token`; discard password after exchange.
- Plex: in-app PIN flow — request PIN from plex.tv (client identifier), show 4-char code +
  "approve at plex.tv/link" (in-app browser button), poll to approval, exchange for account
  token; server discovery via `plex.tv/pms/resources.xml` with manual-URL fallback. No password
  ever stored.
- Per-server auth failures surface as "check credentials" state on the server row; never
  blocking dialogs.

### 3. Source clients (`data/source/`)
- `EmbyLikeClient` core: token header, recursive `/Users/{id}/Items` music queries,
  `/Audio/{id}/stream`, `/Images` art. `JellyfinSource` / `EmbySource` carry base-path/header
  deltas and version quirks only.
- `PlexSource`: sections listing, metadata nesting, direct-play with PMS transcode fallback.
- Each maps to existing `Track`/`Album`; downloads, stream cache, scrobbling, queue, paged
  lists work untouched.

### 4. Sync fan-out (`LibraryRepository`)
- `syncAll()`: local (mode permitting) + each active API server entry sequentially; one
  server's exception skips to the next (same isolation pattern as per-album handling).
- Library mode keeps current meaning; "server" = union of active API servers.
- Removing a server prunes its tracks/albums on next sync (existing deleted-album path);
  playlist orphans clean as today.

### 5. UI
- ServerTab becomes a managed list: rows (name/type badge/URL/status/synced time), per-server
  enable toggle, delete with confirm. "Add server" → type picker → per-type form (URL/user/pass
  + Save & test for Subsonic/Jellyfin/Emby; PIN screen + server picker for Plex).
- Library mode radio unchanged.
- About gains "Support Cadence": BNB Smart Chain + Bitcoin address rows with tap-to-copy +
  toast, above Diagnostics.

## Data flow
Add server → type-specific auth → entry saved → fan-out sync namespaces tracks → paged UI,
counts, search, shuffle operate on the union, filtered by library mode. Plex PIN polls until
approval/timeout; timeout returns to the form with an error line.

## Error handling
Unreachable server / bad credentials / discovery empty → inline row/form error text, entry
kept editable, sync skips it. No retries beyond existing worker policy, no crashes, no
blocking dialogs. Token expiry (Emby/Jellyfin/Plex) → "check credentials" state, re-auth
through the same edit form.

## Testing
- JVM JUnit4: entry JSON round-trip, id namespacing/prefixing, Plex PIN polling state machine
  (fake clock/HTTP), Emby/Jellyfin delta mapping on canned JSON fixtures.
- Build green; device pass: add one of each type (where available), sync, airplane-mode
  playback per server, mode switching across servers, delete-server pruning.
