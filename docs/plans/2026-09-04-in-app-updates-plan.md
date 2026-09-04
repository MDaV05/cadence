# In-App Updates + About Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship GitHub-Releases-based update checks plus an About tab (update rows, links, credits, diagnostics) with zero new dependencies.

**Architecture:** Pure JVM-testable core (`UpdateChecker`: compare + asset-pick on plain data) with a thin Android shell (HTTP fetch, DownloadManager, install intent) in the same file; state and actions live on the existing `AppContainer`; About is a 5th Settings tab reusing existing row patterns.

**Tech Stack:** HttpURLConnection, DownloadManager, FileProvider (core-ktx, already present), JUnit4.

**Spec:** `docs/plans/2026-09-04-in-app-updates-design.md` — the plan argues from the spec; executors read both.

## Global Constraints

- Single `:app` module, minSdk 26 — branch version-code APIs with SDK checks, no new libraries.
- No new architecture layers (no ViewModel/Hilt): state on `AppContainer`, screens collect directly.
- Fail-safe updates: any parse/network/version anomaly → `Failed`/no-update, never a nag dialog.
- Never expose secrets: debug info contains server-configured yes/no only, never password/token.
- Commit per task, conventional lowercase; run `/ponytail-review` before each commit (repo gate).
- Verify each task: `sh gradlew :app:assembleDebug :app:testDebugUnitTest`.

---

### Task 1: UpdateChecker core (TDD)

**Files:**
- Create: `app/src/main/java/com/cadence/music/data/update/UpdateChecker.kt`
- Test: `app/src/test/java/com/cadence/music/data/update/UpdateCheckerTest.kt`

**Interfaces:**
- Consumes: nothing (pure Kotlin + org.json only in the thin fetch fn)
- Produces: `UpdateStatus` sealed interface, `ReleaseInfo`/`ReleaseAsset` data classes,
  `isNewerTag(tag: String, installed: String): Boolean`, `pickApkAsset(assets: List<ReleaseAsset>, tag: String): ReleaseAsset?`, `suspend fetchLatest(): ReleaseInfo?` — used by Task 3

- [ ] **Step 1: Write the failing test**

```kotlin
package com.cadence.music.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `equal versions are not newer`() {
        assertFalse(isNewerTag("v1.2.3", "1.2.3"))
    }

    @Test
    fun `higher patch is newer`() {
        assertTrue(isNewerTag("v1.2.4", "1.2.3"))
    }

    @Test
    fun `shorter tag missing trailing zero is equal`() {
        assertFalse(isNewerTag("v1.2", "1.2.0"))
    }

    @Test
    fun `older major is not newer`() {
        assertFalse(isNewerTag("v0.9.9", "1.0.0"))
    }

    @Test
    fun `unparseable either side means no update`() {
        assertFalse(isNewerTag("nightly", "1.2.3"))
        assertFalse(isNewerTag("v1.2.3", "debug"))
    }

    @Test
    fun `picks exact release apk asset`() {
        val assets = listOf(
            ReleaseAsset("cadence-v1.2.4-debug.apk", "https://example.com/d"),
            ReleaseAsset("cadence-v1.2.4-release.apk", "https://example.com/r"),
        )
        assertEquals("https://example.com/r", pickApkAsset(assets, "v1.2.4")?.url)
    }

    @Test
    fun `missing asset returns null`() {
        assertEquals(null, pickApkAsset(listOf(ReleaseAsset("notes.txt", "https://example.com/n")), "v1.2.4"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sh gradlew :app:testDebugUnitTest --tests "com.cadence.music.data.update.UpdateCheckerTest" 2>&1 | tail -3`
Expected: FAIL with "unresolved reference"

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.cadence.music.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseAsset(val name: String, val url: String)
data class ReleaseInfo(val tag: String, val htmlUrl: String?, val assets: List<ReleaseAsset>)

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val checkedAt: Long = System.currentTimeMillis()) : UpdateStatus
    data class Available(val tag: String, val assetUrl: String, val notesUrl: String?) : UpdateStatus
    data class Failed(val checkedAt: Long = System.currentTimeMillis()) : UpdateStatus
}

/** Numeric segments pairwise; first difference decides; unparseable → false (fail safe). */
fun isNewerTag(tag: String, installed: String): Boolean {
    fun parts(s: String): List<Int>? {
        val nums = s.removePrefix("v").split(".").map { it.takeWhile(Char::isDigit) }
        if (nums.any { it.isEmpty() }) return null
        return nums.map { it.toIntOrNull() ?: return null }
    }
    val a = parts(tag) ?: return false
    val b = parts(installed) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val diff = (a.getOrElse(i) { 0 }) - (b.getOrElse(i) { 0 })
        if (diff != 0) return diff > 0
    }
    return false
}

fun pickApkAsset(assets: List<ReleaseAsset>, tag: String): ReleaseAsset? =
    assets.firstOrNull { it.name == "cadence-$tag-release.apk" }

/** Thin Android shell (HTTP + org.json) — covered by build, not unit tests. */
suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
    runCatching {
        val conn = URL("https://api.github.com/repos/MDaV05/cadence/releases/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "Cadence")
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            val root = JSONObject(conn.inputStream.bufferedReader().readText())
            if (root.optBoolean("prerelease")) return@runCatching null
            val arr = root.optJSONArray("assets") ?: return@runCatching null
            val assets = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ReleaseAsset(o.optString("name"), o.optString("browser_download_url"))
            }
            ReleaseInfo(root.getString("tag_name"), root.optString("html_url", null), assets)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `sh gradlew :app:testDebugUnitTest --tests "com.cadence.music.data.update.UpdateCheckerTest" 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/cadence/music/data/update/UpdateChecker.kt app/src/test/java/com/cadence/music/data/update/UpdateCheckerTest.kt
git commit -m "feat: update checker core with tests"
```

---

### Task 2: Plumbing — prefs flag, manifest, FileProvider paths

**Files:**
- Modify: `app/src/main/java/com/cadence/music/data/prefs/Prefs.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/file_paths.xml`
- Test: `sh gradlew :app:assembleDebug` (manifest/provider merge proof)

**Interfaces:**
- Consumes: nothing
- Produces: `Prefs.updateAutoCheck: Boolean` (default true), `REQUEST_INSTALL_PACKAGES`,
  FileProvider `@xml/file_paths` — used by Tasks 3–4

- [ ] **Step 1: Add the prefs flag** (next to other boolean prefs in `Prefs.kt`)

```kotlin
var updateAutoCheck: Boolean
    get() = sp.getBoolean("update_auto_check", true)
    set(value) = sp.edit().putBoolean("update_auto_check", value).apply()
```

- [ ] **Step 2: Manifest permission + provider** (inside `<application>`, after the receiver block)

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.files"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

(`<uses-permission>` goes with the other permissions at manifest top, NOT inside `<application>`.)

- [ ] **Step 3: Create `res/xml/file_paths.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-files-path name="downloads" path="Download/" />
</paths>
```

- [ ] **Step 4: Verify**

Run: `sh gradlew :app:assembleDebug 2>&1 | tail -2`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/cadence/music/data/prefs/Prefs.kt app/src/main/AndroidManifest.xml app/src/main/res/xml/file_paths.xml
git commit -m "feat: update prefs flag, install permission, file provider"
```

---

### Task 3: Container state + check/download/install actions

**Files:**
- Modify: `app/src/main/java/com/cadence/music/CadenceApp.kt` (`AppContainer` + cold-start trigger)
- Test: build green + existing suite

**Interfaces:**
- Consumes: Task 1 (`UpdateStatus`, `fetchLatest`, `isNewerTag`, `pickApkAsset`), Task 2 (`updateAutoCheck`)
- Produces: `AppContainer.updateStatus: StateFlow<UpdateStatus>`, `suspend refreshUpdateStatus()`,
  `downloadUpdate(tag, assetUrl)`, `installIntent(tag): Intent?` — used by Task 4

- [ ] **Step 1: Add imports to `CadenceApp.kt`**

```kotlin
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.cadence.music.data.update.Available
import com.cadence.music.data.update.Checking
import com.cadence.music.data.update.Failed
import com.cadence.music.data.update.Idle
import com.cadence.music.data.update.UpToDate
import com.cadence.music.data.update.UpdateStatus
import com.cadence.music.data.update.fetchLatest
import com.cadence.music.data.update.isNewerTag
import com.cadence.music.data.update.pickApkAsset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
```

(Import only what the code below uses; drop any unused.)

- [ ] **Step 2: Add state + actions to `AppContainer`** (needs an app Context — add a private val)

```kotlin
class AppContainer(app: Application) {
    private val appContext = app.applicationContext
    // ... existing vals ...

    private val _updateStatus = MutableStateFlow<UpdateStatus>(Idle)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus

    private fun installedVersion(): String = runCatching {
        val pm = appContext.packageManager
        val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(appContext.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(appContext.packageName, 0)
        }
        info.versionName ?: "0.2.0"
    }.getOrDefault("0.2.0")

    /** Runs one check; every failure path lands on Failed, never throws. */
    suspend fun refreshUpdateStatus() {
        _updateStatus.value = Checking
        val rel = fetchLatest()
        val installed = installedVersion()
        val status = if (rel == null || !isNewerTag(rel.tag, installed)) {
            if (rel == null) Failed() else UpToDate()
        } else {
            val asset = pickApkAsset(rel.assets, rel.tag)
            if (asset == null) Failed() else Available(rel.tag, asset.url, rel.htmlUrl)
        }
        _updateStatus.value = status
    }

    /** Enqueues the APK in DownloadManager; progress/completion UI is the system's. */
    fun downloadUpdate(tag: String, assetUrl: String) {
        val req = DownloadManager.Request(Uri.parse(assetUrl))
            .setTitle("Cadence $tag")
            .setDescription("App update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, "cadence-$tag.apk")
            .setMimeType("application/vnd.android.package-archive")
        (appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
    }

    /** In-app install intent for a finished download; null when the file is absent. */
    fun installIntent(tag: String): Intent? {
        val file = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "cadence-$tag.apk")
        if (!file.exists()) return null
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
```

- [ ] **Step 3: Cold-start trigger in `CadenceApp.onCreate`** (next to the existing `appScope.launch` blocks)

```kotlin
// Update check: tiny JSON, any network, never blocks startup, never dialogs.
if (container.prefs.updateAutoCheck) {
    appScope.launch { runCatching { container.refreshUpdateStatus() } }
}
```

- [ ] **Step 4: Verify**

Run: `sh gradlew :app:assembleDebug :app:testDebugUnitTest 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/cadence/music/CadenceApp.kt
git commit -m "feat: container update state and actions"
```

---

### Task 4: About tab with update rows, links, credits

**Files:**
- Modify: `app/src/main/java/com/cadence/music/ui/Screens.kt`
- Test: build green + manual checklist (Step 4)

**Interfaces:**
- Consumes: Task 3 (`updateStatus`, `refreshUpdateStatus`, `downloadUpdate`, `installIntent`, `installedVersion` — private, re-derive display version in UI via same PackageManager snippet? NO: add `fun installedVersionLabel(): String` to container in Task 3? The plan's Task 3 keeps it private. UI needs the version string → Task 4 implementer: read it from `LocalContext.current` PackageManager with the same SDK-33 branch (10 lines, composable-local). Accepted duplication is wrong — better: expose it. RULING (in-plan): make `installedVersion()` public in Task 3 (`fun installedVersion(): String`) and call `container.installedVersion()` from the UI. Task 3 implementer: drop the `private` modifier.
- Produces: 5th Settings tab (leaf UI)

- [ ] **Step 1: Add "About" to the tabs list and `when`**

```kotlin
val tabs = listOf("Appearance", "Server", "Storage", "Playback", "About")
```

```kotlin
4 -> AboutTab(container)
```

- [ ] **Step 2: Write `AboutTab`** (place after `PlaybackTab`, same file; reuse `SectionHeader`, `SettingRow` — read their signatures at `Screens.kt:67-108` first)

```kotlin
@Composable
private fun AboutTab(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val update by container.updateStatus.collectAsStateWithLifecycle(initialValue = Idle)
    var lastTapAvailable by remember { mutableStateOf<Available?>(null) }

    fun statusText(): String = when (val u = update) {
        Idle -> "Never checked"
        Checking -> "Checking…"
        is UpToDate -> "Up to date"
        is Available -> "${u.tag} available — tap to download"
        is Failed -> "Couldn't check for updates"
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("App") }
        item {
            SettingRow(
                title = "Version",
                subtitle = "v${container.installedVersion()}",
            )
        }
        item {
            SettingRow(
                title = "Check for updates",
                subtitle = statusText(),
                trailing = {
                    if (update is Checking) CircularProgressIndicator(Modifier.size(24.dp))
                    else TextButton(onClick = { scope.launch { container.refreshUpdateStatus() } }) {
                        Text("Check now")
                    }
                },
                onClick = {
                    val u = update
                    if (u is Available) {
                        lastTapAvailable = u
                        container.downloadUpdate(u.tag, u.assetUrl)
                    } else {
                        scope.launch { container.refreshUpdateStatus() }
                    }
                },
            )
        }
        val avail = (update as? Available) ?: lastTapAvailable
        if (avail != null && container.installIntent(avail.tag) != null) {
            item {
                SettingRow(
                    title = "Install ${avail.tag}",
                    subtitle = "Download finished — tap to install",
                    onClick = {
                        container.installIntent(avail.tag)?.let { context.startActivity(it) }
                    },
                )
            }
        }
        item {
            SettingRow(
                title = "Auto-check on launch",
                trailing = {
                    Switch(
                        checked = container.prefs.updateAutoCheck,
                        onCheckedChange = { container.prefs.updateAutoCheck = it },
                    )
                },
            )
        }
        val notesUrl = (update as? Available)?.notesUrl
        if (notesUrl != null) {
            item {
                SettingRow(
                    title = "Release notes",
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(notesUrl)))
                    },
                )
            }
        }
        item { SectionHeader("Project") }
        item {
            SettingRow(
                title = "GitHub",
                subtitle = "MDaV05/cadence",
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/MDaV05/cadence")))
                },
            )
        }
        item {
            SettingRow(
                title = "Report an issue",
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/MDaV05/cadence/issues/new")))
                },
            )
        }
        item { SectionHeader("Open source") }
        listOf("Jetpack Compose / Material 3", "Media3", "Room", "Coil", "WorkManager", "Paging").forEach { lib ->
            item { SettingRow(title = lib) }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}
```

Adapt to the ACTUAL `SettingRow` signature in the file (parameters may be `title/subtitle/trailing/onClick` or different — read first, adjust call sites, keep one row per item). Required imports: `collectAsStateWithLifecycle`, `Switch`, `CircularProgressIndicator`, `TextButton`, `Intent`, `Uri`, `rememberCoroutineScope`, `launch`, `LocalContext`, update types. Remove none.

- [ ] **Step 3: Verify**

Run: `sh gradlew :app:assembleDebug :app:testDebugUnitTest 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Manual checklist** (device/emulator if available, else state precisely why skipped)

  - About tab renders version matching installed APK
  - Airplane mode → Check now → "Couldn't check for updates", no crash
  - (Real update only when published): Available row → tap → system download notification → Install row appears → tap → system installer

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/cadence/music/ui/Screens.kt
git commit -m "feat: about tab with in-app updates"
```

---

### Task 5: Diagnostics rows

**Files:**
- Modify: `app/src/main/java/com/cadence/music/data/db/Daos.kt` (`AlbumDao` + `TrackDao` area)
- Modify: `app/src/main/java/com/cadence/music/ui/Screens.kt` (`AboutTab` only)
- Test: build green + manual checklist

**Interfaces:**
- Consumes: Task 4 (`AboutTab` structure), existing `TrackDao.count()`
- Produces: nothing (leaf UI)

- [ ] **Step 1: Add two one-line DAO counts**

```kotlin
// AlbumDao:
@Query("SELECT COUNT(*) FROM albums")
suspend fun count(): Int

// TrackDao (artists):
@Query("SELECT COUNT(DISTINCT artistName) FROM tracks WHERE artistName != ''")
suspend fun artistCount(): Int
```

- [ ] **Step 2: Diagnostics section in `AboutTab`** (after the Open-source section, before the bottom spacer; state + loader)

```kotlin
var diag by remember { mutableStateOf("…") }
LaunchedEffect(Unit) {
    diag = withContext(Dispatchers.IO) {
        val db = container.database
        val tracks = db.trackDao().count()
        val albums = db.albumDao().count()
        val artists = db.trackDao().artistCount()
        val bytes = listOf(
            File(container.files(), "downloads"),
            File(container.cacheDir(), "stream_cache"),
            File(container.cacheDir(), "metadata_images"),
        ).sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        "$tracks tracks • $albums albums • $artists artists • ${formatBytes(bytes)} on disk"
    }
}
```

`container.files()` / `container.cacheDir()` do not exist — `AppContainer` holds no Context reference except the new `appContext` private in Task 3. RULING (in-plan): Task 3 implementer exposes them: add `fun filesDir(): File = appContext.filesDir` and `fun cacheDir(): File = appContext.cacheDir` to `AppContainer` in Task 3 (2 lines; Task 5 consumes). `formatBytes()` already exists in UI package (used by DownloadsScreen — same package? DownloadsScreen is `com.cadence.music.ui`, Screens.kt too — confirm and reuse, do not duplicate).

Section UI:

```kotlin
item { SectionHeader("Diagnostics") }
item {
    SettingRow(
        title = "Library",
        subtitle = diag,
    )
}
item {
    SettingRow(
        title = "Copy debug info",
        subtitle = "Version, counts, mode — no passwords or tokens",
        onClick = {
            scope.launch {
                val info = withContext(Dispatchers.IO) {
                    "Cadence v${container.installedVersion()}\n" +
                        "Library: $diag\n" +
                        "Mode: ${container.prefs.mode}\n" +
                        "Server: ${if (container.prefs.server == null) "none" else "set"}"
                }
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Cadence debug info", info))
                Toast.makeText(context, "Debug info copied", Toast.LENGTH_SHORT).show()
            }
        },
    )
}
```

Required imports: `LaunchedEffect`, `Dispatchers`, `withContext`, `File`, `ClipData`, `ClipboardManager`, `Toast`, `Context`. `scope`/`context` already in `AboutTab` from Task 4.

- [ ] **Step 3: Verify**

Run: `sh gradlew :app:assembleDebug :app:testDebugUnitTest 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Manual checklist** (device if available, else state why skipped)

  - Diagnostics row shows plausible counts matching Library
  - Copy → paste into notes → contains version/counts/mode/server-set, and NO password/token

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/cadence/music/data/db/Daos.kt app/src/main/java/com/cadence/music/ui/Screens.kt
git commit -m "feat: about diagnostics rows"
```

---

### Task 6: Final verification sweep

**Files:** none (verification only)

- [ ] **Step 1: Full build + tests**

Run: `sh gradlew :app:assembleDebug :app:testDebugUnitTest 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Confirm test totals ≥ 40** (33 existing + 7 new)

Run the XML counter over `app/build/test-results/testDebugUnitTest/*.xml`; expect `tests=40 failures=0 errors=0`.

- [ ] **Step 3: Spec cross-check** — every spec section has its task:
  check/parse/compare → Task 1; triggers/download/prefs → Task 3; About rows/links/credits → Task 4;
  version/install/manifest → Tasks 2–3; diagnostics → Task 5; no-license-note honored (no license row).

---

## Self-Review

**1. Spec coverage:** Update check (fetch/parse/compare/prerelease-skip/asset-pick) → Task 1+3. Triggers (cold start/manual) + DownloadManager + install intent + prefs flag → Tasks 2+3. About rows (version/update/auto-check/notes) → Task 4. Links + credits → Task 4. Diagnostics (counts/storage/copy, no secrets) → Task 5. Manifest/provider → Task 2. No-license deviation honored. No gaps.

**2. Placeholder scan:** No TBD/TODO/"appropriate". All code blocks complete; `SettingRow` variance handled by an explicit read-first instruction (signature lives in-repo, cannot be quoted blind).

**3. Type consistency:** `UpdateStatus`/`ReleaseInfo`/`ReleaseAsset`/`isNewerTag`/`pickApkAsset`/`fetchLatest` spelled identically in Tasks 1/3/4. `updateStatus: StateFlow<UpdateStatus>`, `refreshUpdateStatus()` suspend, `downloadUpdate(tag, assetUrl)`, `installIntent(tag): Intent?`, `installedVersion(): String` (public per Task 4 ruling), `filesDir()/cacheDir(): File` (Task 3 exposes, Task 5 consumes). DAO `count()/artistCount(): Int` suspend. Prefs `updateAutoCheck: Boolean`.
