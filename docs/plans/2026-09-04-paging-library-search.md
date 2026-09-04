# Paging for Library + Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace full-table `observeAll()` + in-memory sort/filter in Library songs tab and Search with Paging 3 backed by Room, so 50k-track libraries scroll at 60fps with bounded memory.

**Architecture:** Room `@RawQuery` returning `PagingSource` (dynamic ORDER BY can't be a static `@Query`); pure-Kotlin SQL builder (`TrackQueries`) unit-tested on JVM; repository exposes `Flow<PagingData>`; Compose screens collect via `paging-compose`. Artist/Album screens keep `List` (bounded to one artist/album). `observeAll()` stays for Home shuffle-all only (user-initiated one-shot).

**Tech Stack:** Paging 3.3.x (`paging-runtime-ktx`, `paging-compose`, `paging-testing`), Room 2.7.2 RawQuery, JUnit4 (existing test setup).

**Spec:** `docs/plans/2026-08-21-android-music-player-design.md` (Phase 2 done-when: 50k tracks browse <300ms after sync; scanner/sync targets). Sort parity reference: `sortSongs()` in `app/src/main/java/com/cadence/music/ui/LibraryScreen.kt:125-147`.

## Global Constraints

- Single `:app` module, minSdk 26 — no API-level-gated paging APIs without fallback.
- No new architecture layers (no ViewModel, no Hilt): repository returns `Flow<PagingData>`, screens `cachedIn(rememberCoroutineScope())`, matching current style.
- Sort behavior must match `sortSongs()` exactly: text sorts case-insensitive, `RECENTLY_PLAYED` treats null as 0, `MOST_PLAYED` tie-breaks on `lastPlayed`, descending reverses ALL keys.
- Commit per task, conventional lowercase (`feat:`, `fix:`, `chore:`); run `/ponytail-review` before each commit (repo gate).
- Verify each task: `sh gradlew :app:assembleDebug :app:testDebugUnitTest`.

---

## Task 1: Paging dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Test: dependency resolution output

**Interfaces:**
- Consumes: nothing
- Produces: `libs.paging.runtime`, `libs.paging.compose`, `libs.paging.testing` aliases used by Tasks 4–6

- [ ] **Step 1: Pin the latest stable Paging 3.3.x version**

Run: `curl -s https://dl.google.com/dl/android/maven2/androidx/paging/paging-runtime/maven-metadata.xml | grep latest`
Expected: a version like `3.3.x`. Use that exact version below (the plan writes `3.3.5`; substitute if newer).

- [ ] **Step 2: Add version + libraries to the catalog**

```toml
paging = "3.3.5"
```

```toml
paging-runtime = { group = "androidx.paging", name = "paging-runtime-ktx", version.ref = "paging" }
paging-compose = { group = "androidx.paging", name = "paging-compose", version.ref = "paging" }
paging-testing = { group = "androidx.paging", name = "paging-testing", version.ref = "paging" }
```

Place the version next to `work = "2.10.0"` and the libraries next to the `work-runtime-ktx` line, keeping alphabetical grouping.

- [ ] **Step 3: Add runtime deps to the app module**

```kotlin
implementation(libs.paging.runtime)
implementation(libs.paging.compose)
testImplementation(libs.paging.testing)
```

Place after the `libs.work.runtime.ktx` line in `app/build.gradle.kts`.

- [ ] **Step 4: Verify resolution**

Run: `sh gradlew :app:assembleDebug 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "chore: add paging3 dependencies"
```

---

## Task 2: Pure-Kotlin query builder (TDD)

**Files:**
- Create: `app/src/main/java/com/cadence/music/data/db/TrackQueries.kt`
- Test: `app/src/test/java/com/cadence/music/data/db/TrackQueriesTest.kt`

**Interfaces:**
- Consumes: `Prefs.SongSort` enum (`TITLE, ARTIST, ALBUM, DURATION, RECENTLY_ADDED, RECENTLY_PLAYED, MOST_PLAYED`)
- Produces: `TrackQueries.tracksQuery(sort: SongSort, ascending: Boolean): SupportSQLiteQuery`, `TrackQueries.searchQuery(raw: String): SupportSQLiteQuery` — used by Task 3

- [ ] **Step 1: Write the failing test** (`TrackQueriesTest.kt`)

`SimpleSQLiteQuery` exposes only `sql`/`argCount` on JVM (args bind to a native statement), so escaping is tested through the package-visible `escapeLike()` helper:

```kotlin
package com.cadence.music.data.db

import com.cadence.music.data.prefs.Prefs.SongSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackQueriesTest {

    @Test
    fun `title ascending is case-insensitive`() {
        val q = TrackQueries.tracksQuery(SongSort.TITLE, true)
        assertEquals("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC", q.sql)
    }

    @Test
    fun `descending reverses all keys including tiebreak`() {
        val q = TrackQueries.tracksQuery(SongSort.MOST_PLAYED, false)
        assertEquals(
            "SELECT * FROM tracks ORDER BY playCount DESC, COALESCE(lastPlayed, 0) DESC",
            q.sql,
        )
    }

    @Test
    fun `recently played nulls sort as zero`() {
        val q = TrackQueries.tracksQuery(SongSort.RECENTLY_PLAYED, true)
        assertTrue(q.sql.contains("COALESCE(lastPlayed, 0) ASC"))
    }

    @Test
    fun `all seven sorts produce distinct order clauses`() {
        val sqls = SongSort.values().map { TrackQueries.tracksQuery(it, true).sql }.toSet()
        assertEquals(7, sqls.size)
    }

    @Test
    fun `like metacharacters are escaped`() {
        assertEquals("%100\\%\\_x\\\\y%", "%${TrackQueries.escapeLike("100%_x\\y")}%")
    }

    @Test
    fun `search binds three args with escape clause`() {
        val q = TrackQueries.searchQuery("love")
        assertEquals(3, q.argCount)
        assertTrue(q.sql.contains("title LIKE ? ESCAPE '\\'"))
        assertTrue(q.sql.contains("artistName LIKE ? ESCAPE '\\'"))
        assertTrue(q.sql.contains("albumName LIKE ? ESCAPE '\\'"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sh gradlew :app:testDebugUnitTest --tests "com.cadence.music.data.db.TrackQueriesTest" 2>&1 | tail -5`
Expected: FAIL with "unresolved reference: TrackQueries"

- [ ] **Step 3: Write minimal implementation** (`TrackQueries.kt`)

```kotlin
package com.cadence.music.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.cadence.music.data.prefs.Prefs.SongSort

/** Pure-SQL builders for paged track queries. No Android calls — JVM-testable. */
object TrackQueries {

    fun tracksQuery(sort: SongSort, ascending: Boolean): SupportSQLiteQuery {
        val dir = if (ascending) "ASC" else "DESC"
        // Mirrors sortSongs() semantics exactly: NOCASE text, null lastPlayed as 0,
        // playCount tie-broken by lastPlayed, descending reverses every key.
        val order = when (sort) {
            SongSort.TITLE -> "title COLLATE NOCASE $dir"
            SongSort.ARTIST -> "artistName COLLATE NOCASE $dir"
            SongSort.ALBUM -> "albumName COLLATE NOCASE $dir"
            SongSort.DURATION -> "durationMs $dir"
            SongSort.RECENTLY_ADDED -> "id $dir"
            SongSort.RECENTLY_PLAYED -> "COALESCE(lastPlayed, 0) $dir"
            SongSort.MOST_PLAYED -> "playCount $dir, COALESCE(lastPlayed, 0) $dir"
        }
        return SimpleSQLiteQuery("SELECT * FROM tracks ORDER BY $order")
    }

    fun searchQuery(raw: String): SupportSQLiteQuery {
        val like = "%${escapeLike(raw)}%"
        return SimpleSQLiteQuery(
            "SELECT * FROM tracks WHERE title LIKE ? ESCAPE '\\' " +
                "OR artistName LIKE ? ESCAPE '\\' OR albumName LIKE ? ESCAPE '\\' " +
                "ORDER BY title COLLATE NOCASE",
            arrayOf(like, like, like),
        )
    }

    /** Escapes LIKE metacharacters; caller wraps result in %...%. */
    fun escapeLike(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) {
            if (c == '%' || c == '_' || c == '\\') out.append('\\')
            out.append(c)
        }
        return out.toString()
    }
}
```

(`SimpleSQLiteQuery`/`SupportSQLiteQuery` live in `androidx.sqlite:sqlite`, already on the classpath transitively via Room; both are plain JVM classes, safe in unit tests.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `sh gradlew :app:testDebugUnitTest --tests "com.cadence.music.data.db.TrackQueriesTest" 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/cadence/music/data/db/TrackQueries.kt app/src/test/java/com/cadence/music/data/db/TrackQueriesTest.kt
git commit -m "feat: pure-kotlin paged track query builder with tests"
```

---

## Task 3: DAO RawQuery paging source

**Files:**
- Modify: `app/src/main/java/com/cadence/music/data/db/Daos.kt` (`TrackDao` interface only)
- Test: `sh gradlew :app:assembleDebug` (compile proof; behavior covered by Task 2 + Task 6 manual check)

**Interfaces:**
- Consumes: `TrackQueries` output type (`SupportSQLiteQuery`) from Task 2
- Produces: `TrackDao.tracksPaged(query: SupportSQLiteQuery): PagingSource<Int, TrackEntity>` used by Task 4

- [ ] **Step 1: Add imports + method to `TrackDao`**

```kotlin
import androidx.paging.PagingSource
import androidx.sqlite.db.SupportSQLiteQuery
```

```kotlin
@RawQuery(observedEntities = [TrackEntity::class])
fun tracksPaged(query: SupportSQLiteQuery): PagingSource<Int, TrackEntity>
```

Place the method directly after `observeAll()`. `observedEntities` keeps the pager subscribed to `tracks` table changes (sync inserts re-emit pages automatically).

- [ ] **Step 2: Verify compile**

Run: `sh gradlew :app:assembleDebug 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/cadence/music/data/db/Daos.kt
git commit -m "feat: rawquery paging source for tracks"
```

---

## Task 4: Repository paged flows

**Files:**
- Modify: `app/src/main/java/com/cadence/music/data/LibraryRepository.kt`
- Test: compile proof + existing suite green

**Interfaces:**
- Consumes: `TrackDao.tracksPaged` (Task 3), `TrackQueries` (Task 2)
- Produces: `LibraryRepository.tracksPaged(sort, ascending): Flow<PagingData<TrackEntity>>`, `LibraryRepository.searchPaged(query): Flow<PagingData<TrackEntity>>` used by Tasks 5–6

- [ ] **Step 1: Add imports**

```kotlin
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.cadence.music.data.db.TrackQueries
import com.cadence.music.data.prefs.Prefs.SongSort
```

- [ ] **Step 2: Add the two functions** (place after `fun tracks()`)

```kotlin
// Paged reads for big libraries; pageSize 50 ≈ 3 screens, maxSize 300 bounds memory,
// no placeholders (counts shift during sync anyway).
private val trackPagingConfig = PagingConfig(pageSize = 50, maxSize = 300, enablePlaceholders = false)

fun tracksPaged(sort: SongSort, ascending: Boolean): Flow<PagingData<TrackEntity>> =
    Pager(trackPagingConfig) { db.trackDao().tracksPaged(TrackQueries.tracksQuery(sort, ascending)) }.flow

fun searchPaged(query: String): Flow<PagingData<TrackEntity>> =
    Pager(trackPagingConfig) { db.trackDao().tracksPaged(TrackQueries.searchQuery(query)) }.flow
```

- [ ] **Step 3: Document the `tracks()` exception** — change its declaration to:

```kotlin
// Full-list read kept ONLY for one-shot shuffle-all (Home + Library FAB).
// All scrolling UI must use tracksPaged()/searchPaged().
fun tracks(): Flow<List<TrackEntity>> = db.trackDao().observeAll()
```

- [ ] **Step 4: Verify**

Run: `sh gradlew :app:assembleDebug :app:testDebugUnitTest 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/cadence/music/data/LibraryRepository.kt
git commit -m "feat: paged track and search flows in repository"
```

---

## Task 5: Library songs tab → paging

**Files:**
- Modify: `app/src/main/java/com/cadence/music/ui/LibraryScreen.kt`
- Test: full suite + manual checklist (see Step 4)

**Interfaces:**
- Consumes: `LibraryRepository.tracksPaged` (Task 4)
- Produces: no new API; `sortSongs()` DELETED after this task

- [ ] **Step 1: Change `songsTab` signature and body**

Old signature:

```kotlin
private fun songsTab(
    tracks: List<TrackEntity>,
    container: AppContainer,
    onArtistClick: (String) -> Unit,
    player: com.cadence.music.playback.PlayerConnection,
)
```

New signature (drop the full-list param; sort state stays local):

```kotlin
private fun songsTab(
    container: AppContainer,
    onArtistClick: (String) -> Unit,
    player: com.cadence.music.playback.PlayerConnection,
)
```

Body changes:
1. Keep the existing `sort`/`ascending`/`sortMenu` state and prefs writes untouched.
2. Replace `val sorted = remember(tracks, sort, ascending) { sortSongs(tracks, sort, ascending) }` with:

```kotlin
val scope = rememberCoroutineScope() // reuse the existing scope at the top of songsTab
val pagingItems = remember(sort, ascending) {
    container.library.tracksPaged(sort, ascending)
}.cachedIn(scope).collectAsLazyPagingItems()
```

NOTE: `songsTab` already declares `val scope = rememberCoroutineScope()` at its top — reuse it, do not add a second one.
3. Update the call site (`when (tab) { 0 -> songsTab(...) }`) to drop the `tracks` argument.
4. Replace the list body:

```kotlin
LazyColumn(Modifier.fillMaxSize()) {
    items(
        count = pagingItems.itemCount,
        key = pagingItems.itemKey { it.id },
    ) { i ->
        pagingItems[i]?.let { track ->
            TrackRow(container, track, onArtistClick) { player.playNow(listOf(track.toTrack())) }
        }
    }
    when (val s = pagingItems.loadState.refresh) {
        is LoadState.Loading -> if (pagingItems.itemCount == 0) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            } }
        }
        is LoadState.Error -> if (pagingItems.itemCount == 0) {
            item { Text(
                "Couldn't load songs.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(32.dp),
            ) }
        }
        else -> Unit
    }
    if (pagingItems.loadState.refresh is LoadState.NotLoading && pagingItems.itemCount == 0) {
        item { Text(
            "No songs yet — sync your library first.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        ) }
    }
}
```

5. The `if (tracks.isEmpty()) { EmptyLibrary(...); return }` gate above stays as-is (still driven by the full-list `tracks` flow kept for the FAB).
6. Delete `sortSongs()` entirely.

- [ ] **Step 2: Add imports, remove dead ones**

Add: `androidx.compose.foundation.layout.Box`, `androidx.compose.material3.CircularProgressIndicator`, `androidx.paging.LoadState`, `androidx.paging.compose.collectAsLazyPagingItems`, `androidx.paging.compose.itemKey`, `androidx.paging.cachedIn`. Remove nothing else (`TrackEntity` import still used by other tabs).

- [ ] **Step 3: Verify compile + tests**

Run: `sh gradlew :app:assembleDebug :app:testDebugUnitTest 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Manual checklist** (emulator or device, ~2k+ track library ideally)

  - Songs tab scrolls smoothly; sort menu changes order for Title A→Z, Z→A, Most played
  - Rotate device: scroll position + sort survive (sort already in Prefs)
  - Trigger a library sync while on Songs tab: list updates without crash
  - Shuffle-all FAB still plays

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/cadence/music/ui/LibraryScreen.kt
git commit -m "feat: page library songs tab from room"
```

---

## Task 6: Search → DB-side paged search + debounce

**Files:**
- Modify: `app/src/main/java/com/cadence/music/ui/SearchScreen.kt`
- Test: full suite + manual checklist

**Interfaces:**
- Consumes: `LibraryRepository.searchPaged` (Task 4)
- Produces: nothing (leaf screen)

- [ ] **Step 1: Replace in-memory filter with debounced pager**

1. Delete `val all by container.library.tracks().collectAsStateWithLifecycle(...)` (line 50).
2. Add debounce state after `var query`:

```kotlin
var debounced by rememberSaveable { mutableStateOf("") }
LaunchedEffect(query) {
    delay(300)
    debounced = query
}
```

3. Replace `val results = if (query.isBlank()) ...` with:

```kotlin
val scope = rememberCoroutineScope()
val pagingItems = remember(debounced) {
    container.library.searchPaged(debounced)
}.cachedIn(scope).collectAsLazyPagingItems()
val resultCount = pagingItems.itemCount
```

4. Change the results header + list to use paging (same `items(count, key)` + load-state pattern as Task 5). Keep `recordQuery(query)` + `focusManager.clearFocus()` in the row click. Show the result-count text only when `query.isNotBlank()`. Gate the whole results branch on `query.isNotBlank()` as today (so no DB query fires for the empty string — `remember("")` flow would still run; guard by only collecting when non-blank):

```kotlin
} else {
    key(debounced) { SearchResults(...) } // or inline: only build the pager when query non-blank
}
```

Simplest inline form: keep the existing `if (query.isBlank()) { history... } else { results... }` structure; inside the else branch, build the pager from `debounced` (initially "" → shows count header only once debounce lands; acceptable and flicker-free because the branch shows the count text `"$resultCount results"` which starts at 0).

5. Add imports: `delay`, `LaunchedEffect`, `rememberCoroutineScope`, `key` (if used), `androidx.paging.*` compose helpers, `cachedIn`. Remove `collectAsStateWithLifecycle` import if now unused in this file.

- [ ] **Step 2: Verify**

Run: `sh gradlew :app:assembleDebug :app:testDebugUnitTest 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Manual checklist**

  - Type "ab": results appear ≤400ms after last keystroke, no per-keystroke jank
  - Type `%` and `_`: no crash, literal-match behavior (escaped)
  - Empty query shows history chips; tapping a chip searches
  - Airplane mode: search still works (local DB)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/cadence/music/ui/SearchScreen.kt
git commit -m "feat: db-side paged search with debounce"
```

---

## Task 7: Final verification + leftover sweep

**Files:** none (verification only)

- [ ] **Step 1: Full build + tests**

Run: `sh gradlew :app:assembleDebug :app:testDebugUnitTest 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Confirm no remaining full-table scroll paths**

Run: `rg -n "observeAll\(\)|sortSongs" app/src/main --no-heading`
Expected: only `observeAll` definition + `tracks()` in `LibraryRepository.kt`, collectors in `LibraryScreen.kt` (FAB gate) and `HomeScreen.kt` (shuffle-all). `sortSongs`: no matches.

- [ ] **Step 3: Confirm test totals**

Run the Python XML counter used before; expect ≥28 tests (22 existing + 6 new), 0 failures.

---

## Self-Review

**1. Spec coverage:** Phase 2 done-when items map as — browse <300ms after sync → Tasks 5+6 (DB-side order/filter, 50-item pages); airplane-mode playback → untouched (out of scope, already works); download/queue behavior → untouched. No spec requirement lacks a task. Explicitly out of scope (stated, not missing): shuffle-all full-list read (documented exception in Task 4), Paging placeholders off (counts shift during sync), MetadataWorker batching (already landed).

**2. Placeholder scan:** No TBD/TODO/"appropriate handling". Version pin has a concrete verify-and-substitute step, not a guess. Every code block is complete and copy-pasteable; all referenced symbols (`itemKey`, `cachedIn`, `LoadState`, `SimpleSQLiteQuery`, `SongSort`) exist in the named artifacts.

**3. Type consistency:** `tracksPaged(query: SupportSQLiteQuery)` ↔ `TrackQueries.*` return `SupportSQLiteQuery` ✓. `Pager(PagingConfig) { PagingSource }` → `.flow: Flow<PagingData<TrackEntity>>` ✓. `itemKey { it.id }` on `LazyPagingItems<TrackEntity>` ✓. `q.sql: String`, `q.argCount: Int` on `SupportSQLiteQuery` ✓. `SongSort.values()` (Java enum from Kotlin: `entries` also works on 1.9+; `values()` is safe on all versions — check `build.gradle.kts` Kotlin version if `entries` preferred; `values()` compiles everywhere).

## Open questions (answer before Task 2 if they change SQL)

1. RECENTLY_PLAYED descending: `cmp.reversed()` puts never-played (`0`) LAST in desc. `COALESCE(lastPlayed,0) DESC` matches exactly. ✓ No question — parity verified against `sortSongs()`.
2. `maxSize = 300` drops distant pages; scroll jump re-fetches from Room (cheap, indexed `tracks` rowid). Acceptable.
