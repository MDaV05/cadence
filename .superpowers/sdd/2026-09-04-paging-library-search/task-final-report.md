# task-final-report — fix wave: search debounce gate, mode-aware aggregates, mode re-tap guard

## Fixes (one commit, 4 files)
1. **Search debounce gate** (`ui/SearchScreen.kt`): non-blank-`query` branch now shows a centered
   `CircularProgressIndicator` (32.dp padding, existing imports) while `debounced.isBlank()`;
   the pager is only built from `debounced` once non-blank. Kills the 300ms `LIKE '%%'` full-library flash.
2. **Mode-aware aggregates** (`data/db/Daos.kt`, `data/LibraryRepository.kt`): added
   `observeAlbumGroupsFor(sources)` / `observeArtistNamesFor(sources)` (`AND sourceId IN (:sources)`);
   `albumGroups()` / `artistNames()` are now `prefs.observeMode().flatMapLatest` + `sourcesFor(mode)`
   (`null` = existing unfiltered queries). Signatures unchanged.
3. **Mode re-tap guard** (`ui/Screens.kt:416`): RadioButton onClick wrapped in `if (m != mode) { … }`
   with the exact existing statements inside — re-tapping the active mode no longer fires `syncAll()`.

## Collector check
`rg -n "albumGroups\(\)|artistNames\(\)" app/src/main` → only `LibraryScreen.kt:81-84` collects;
`HomeScreen` does not use these flows (uses raw DAO suspends `mostPlayed`/`recentlyPlayed`/
`recentlyAdded`/`count` for shelves — unfiltered, pre-existing, out of scope). No other collectors break.

## Verify
- `sh gradlew :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL
- Unit tests: **33 tests, 0 failures, 0 errors, 0 skipped**
- Ponytail gate: pass — all three changes requested, minimal, reuse established `sourcesFor`/`observeMode`/
  DAO patterns; SearchScreen re-indent churn is inherent to the inner-else wrap. No waivers.
