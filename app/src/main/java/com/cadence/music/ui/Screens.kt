package com.cadence.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cadence.music.AppContainer
import com.cadence.music.data.db.CustomThemeEntity
import com.cadence.music.data.prefs.LibraryMode
import com.cadence.music.data.prefs.Prefs
import com.cadence.music.data.update.UpdateStatus.Available
import com.cadence.music.data.update.UpdateStatus.Checking
import com.cadence.music.data.update.UpdateStatus.Failed
import com.cadence.music.data.update.UpdateStatus.Idle
import com.cadence.music.data.update.UpdateStatus.UpToDate
import com.cadence.music.playback.EqManager
import com.cadence.music.ui.theme.BUILTIN_THEMES
import com.cadence.music.ui.theme.ThemeSpec
import com.cadence.music.ui.theme.customToSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun SectionHeader(text: String) {
    Column {
        Spacer(Modifier.height(24.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenEqualizer: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Appearance", "Server", "Storage", "Playback", "About")

    Column(Modifier.fillMaxSize()) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        when (tab) {
            0 -> AppearanceTab(container)
            1 -> ServerTab(container)
            2 -> StorageTab(container, onOpenDownloads)
            3 -> PlaybackTab(container, onOpenEqualizer)
            4 -> AboutTab(container)
        }
    }
}

// ---- Appearance ----

@Composable
private fun AppearanceTab(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val prefs = container.prefs
    var showNewTheme by remember { mutableStateOf(false) }
    // Snapshot read so the list recomposes when themes are added/removed.
    val customSpecs = container.customThemes.map { customToSpec(it) }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Theme") }
        items(BUILTIN_THEMES + customSpecs, key = { it.id }) { spec ->
            ThemeCard(
                spec = spec,
                selected = prefs.themeId == spec.id,
                onSelect = {
                    prefs.themeId = spec.id
                    container.refreshTheme()
                },
                onDelete = if (spec.id.startsWith("custom:")) {
                    {
                        scope.launch {
                            container.database.themeDao().delete(spec.name)
                            container.loadCustomThemes()
                            if (prefs.themeId == spec.id) {
                                prefs.themeId = "iris"
                                container.refreshTheme()
                            }
                        }
                    }
                } else null,
            )
        }
        item {
            TextButton(
                onClick = { showNewTheme = true },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) { Text("＋ New theme") }
        }
        item { SectionHeader("Dark mode") }
        item {
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    null to "Follow system",
                    false to "Always light",
                    true to "Always dark",
                ).forEach { (dark, label) ->
                    val selected = if (prefs.themeFollowSystem) dark == null
                    else dark != null && prefs.themeDarkOverride == dark
                    FilterChip(
                        selected = selected,
                        onClick = {
                            when (dark) {
                                null -> prefs.themeFollowSystem = true
                                else -> {
                                    prefs.themeFollowSystem = false
                                    prefs.themeDarkOverride = dark
                                }
                            }
                            container.refreshTheme()
                        },
                        label = { Text(label) },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }

    if (showNewTheme) {
        NewThemeDialog(container, onDismiss = { showNewTheme = false })
    }
}

@Composable
private fun ThemeCard(
    spec: ThemeSpec,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // Light + dark previews: background tile with the accent as a dot.
        ThemeSwatch(spec.bgLight, spec.accentLight)
        Spacer(Modifier.size(4.dp))
        ThemeSwatch(spec.bgDark, spec.accentDark)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(spec.name)
            if (selected) {
                Text(
                    "Active",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (selected) {
            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Close, "Delete ${spec.name}")
            }
        }
    }
}

@Composable
private fun ThemeSwatch(bg: Int, accent: Int) {
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(bg)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(accent)),
        )
    }
}

private fun parseHex(s: String): Int? =
    if (Regex("^[0-9A-Fa-f]{6}$").matches(s)) (0xFF000000L or s.toLong(16)).toInt() else null

@Composable
private fun HexField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        singleLine = true,
        leadingIcon = parseHex(value)?.let { c -> { Box(Modifier.size(18.dp).clip(CircleShape).background(Color(c))) } },
        trailingIcon = if (value.isNotEmpty() && parseHex(value) == null) {
            { Text("?", color = MaterialTheme.colorScheme.error) }
        } else null,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NewThemeDialog(container: AppContainer, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var aL by remember { mutableStateOf("6B4EE8") }
    var aD by remember { mutableStateOf("9D8BFF") }
    var bL by remember { mutableStateOf("FAFAFC") }
    var bD by remember { mutableStateOf("0E0E13") }
    val valid = name.isNotBlank() && listOf(aL, aD, bL, bD).all { parseHex(it) != null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New theme") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                HexField("Accent — light mode", aL, { aL = it })
                HexField("Accent — dark mode", aD, { aD = it })
                HexField("Background — light mode", bL, { bL = it })
                HexField("Background — dark mode", bD, { bD = it })
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                scope.launch {
                    container.database.themeDao().upsert(
                        CustomThemeEntity(
                            name = name.trim(),
                            accentLight = parseHex(aL)!!,
                            accentDark = parseHex(aD)!!,
                            bgLight = parseHex(bL)!!,
                            bgDark = parseHex(bD)!!,
                        )
                    )
                    container.loadCustomThemes()
                    container.prefs.themeId = "custom:${name.trim()}"
                    container.refreshTheme()
                }
                onDismiss()
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- Server ----

@Composable
private fun ServerTab(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val server = container.prefs.server
    var url by remember { mutableStateOf(server?.url ?: "") }
    var user by remember { mutableStateOf(server?.user ?: "") }
    var pass by remember { mutableStateOf(server?.password ?: "") }
    var mode by remember { mutableStateOf(container.prefs.mode) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Server") }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    pass, { pass = it },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && url.isNotBlank() && user.isNotBlank(),
                        onClick = {
                            busy = true; status = ""
                            scope.launch {
                                try {
                                    // Scheme-less URLs ("192.168.1.106:4533") would fail silently on Android 9+.
                                    val normalized = url.trim().let { if (it.contains("://")) it else "http://$it" }
                                    container.prefs.server =
                                        com.cadence.music.data.prefs.ServerConfig(normalized, user, pass)
                                    status = if (container.library.subsonic.ping()) {
                                        val n = container.library.syncServer()
                                        "Connected — synced ${n.tracksFetched} tracks (${n.albumsFetched} albums)"
                                    } else "Connection failed"
                                } catch (e: Exception) {
                                    status = "Error: ${e.message}"
                                }
                                busy = false
                            }
                        },
                    ) { Text("Save & sync") }

                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true; status = ""
                            scope.launch {
                                runCatching { container.library.syncAll() }
                                    .onSuccess { status = "Library synced" }
                                    .onFailure { status = "Sync error: ${it.message}" }
                                busy = false
                            }
                        },
                    ) { Text("Rescan") }
                }
                if (busy) CircularProgressIndicator(Modifier.padding(top = 8.dp))
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }

        item { SectionHeader("Library mode") }
        items(LibraryMode.entries.toList()) { m ->
            SettingRow(
                title = when (m) {
                    LibraryMode.LOCAL_ONLY -> "Local files only"
                    LibraryMode.API_ONLY -> "Server (API) only"
                    LibraryMode.HYBRID -> "Local + server"
                },
                trailing = {
                    RadioButton(selected = mode == m, onClick = {
                        if (m != mode) { mode = m; container.prefs.mode = m; scope.launch { runCatching { container.library.syncAll() } } }
                    })
                },
            )
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ---- Storage ----

@Composable
private fun StorageTab(container: AppContainer, onOpenDownloads: () -> Unit) {
    val dlFormat = remember { mutableStateOf(container.prefs.downloadFormat) }
    val dlBitrate = remember { mutableIntStateOf(container.prefs.downloadBitrate) }
    val cacheGb = remember { mutableIntStateOf(container.prefs.cacheGb) }
    val cacheUsage by produceCacheUsage()

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Downloads") }
        item {
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("raw" to "Original", "opus" to "Opus", "mp3" to "MP3").forEach { (f, label) ->
                    FilterChip(
                        selected = dlFormat.value == f,
                        onClick = { dlFormat.value = f; container.prefs.downloadFormat = f },
                        label = { Text(label) },
                    )
                }
            }
        }
        if (dlFormat.value != "raw") {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Bitrate: ${dlBitrate.value} kbps", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = dlBitrate.value.toFloat(),
                        onValueChange = { dlBitrate.value = it.toInt() },
                        onValueChangeFinished = { container.prefs.downloadBitrate = dlBitrate.value },
                        valueRange = 64f..320f,
                    )
                }
            }
        }

        item { SectionHeader("Stream cache") }
        item {
            SettingRow(
                title = "Size limit: ${cacheGb.value} GB",
                subtitle = cacheUsage?.let { used -> "Currently using ${"%.1f".format(used / (1024f * 1024 * 1024))} GB — applies after restart" },
            )
        }
        item {
            Slider(
                value = cacheGb.value.toFloat(),
                onValueChange = { cacheGb.value = it.toInt() },
                onValueChangeFinished = { container.prefs.cacheGb = cacheGb.value },
                valueRange = 1f..8f,
                steps = 6,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        item { SectionHeader("Offline") }
        item {
            val downloadCount by produceState(0) {
                value = withContext(Dispatchers.IO) {
                    runCatching { container.library.observeDownloads().first().size }.getOrDefault(0)
                }
            }
            SettingRow(
                title = "Downloads",
                subtitle = when (downloadCount) {
                    0 -> "Nothing offline yet"
                    else -> "$downloadCount track${if (downloadCount == 1) "" else "s"} downloaded"
                },
                onClick = onOpenDownloads,
            )
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ---- Playback ----

@Composable
private fun PlaybackTab(container: AppContainer, onOpenEqualizer: () -> Unit) {
    val context = LocalContext.current
    val gesture = remember { mutableStateOf(container.prefs.trackGesture) }
    val eqEnabled = remember { mutableStateOf(container.prefs.eqEnabled) }
    val rgEnabled = remember { mutableStateOf(container.prefs.rgEnabled) }
    var lbToken by remember { mutableStateOf(container.prefs.listenBrainzToken ?: "") }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Playback") }
        item {
            Text(
                "Track change gesture",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = gesture.value == Prefs.TrackGesture.HORIZONTAL,
                    onClick = { gesture.value = Prefs.TrackGesture.HORIZONTAL; container.prefs.trackGesture = Prefs.TrackGesture.HORIZONTAL },
                    label = { Text("Swipe left/right") },
                )
                FilterChip(
                    selected = gesture.value == Prefs.TrackGesture.VERTICAL,
                    onClick = { gesture.value = Prefs.TrackGesture.VERTICAL; container.prefs.trackGesture = Prefs.TrackGesture.VERTICAL },
                    label = { Text("Swipe up/down") },
                )
            }
        }

        item {
            SettingRow(
                title = "ReplayGain normalization",
                subtitle = "Levels playback using file tags — rescan library to read them",
                trailing = {
                    Switch(
                        checked = rgEnabled.value,
                        onCheckedChange = { rgEnabled.value = it; container.prefs.rgEnabled = it },
                    )
                },
            )
        }

        item { SectionHeader("Equalizer") }
        item {
            SettingRow(
                title = "Equalizer & bass boost",
                subtitle = if (eqEnabled.value) "On — ${EqManager.bandCount} bands" else "Off",
                trailing = {
                    Switch(
                        checked = eqEnabled.value,
                        onCheckedChange = {
                            eqEnabled.value = it
                            container.prefs.eqEnabled = it
                        },
                    )
                },
                onClick = onOpenEqualizer,
            )
        }

        item { SectionHeader("Metadata & lyrics") }
        item { MetadataSection(container) }

        item { SectionHeader("Scrobbling") }
        item {
            OutlinedTextField(
                lbToken,
                {
                    lbToken = it
                    container.prefs.listenBrainzToken = it.ifBlank { null }
                },
                label = { Text("ListenBrainz user token") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
            )
        }
        item {
            Text(
                "Get your token at listenbrainz.org/profile",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun AboutTab(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val update by container.updateStatus.collectAsStateWithLifecycle(initialValue = Idle)
    var lastTapAvailable by remember { mutableStateOf<Available?>(null) }
    var autoCheck by remember { mutableStateOf(container.prefs.updateAutoCheck) }

    fun statusText(): String = when (val u = update) {
        Idle -> "Never checked"
        Checking -> "Checking…"
        is UpToDate -> "Up to date"
        is Available -> "${u.tag} available — tap to download"
        is Failed -> "Couldn't check for updates"
    }

    val avail = (update as? Available) ?: lastTapAvailable
    val pendingInstall = avail?.takeIf { container.installIntent(it.tag) != null }

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
        if (pendingInstall != null) {
            item {
                SettingRow(
                    title = "Install ${pendingInstall.tag}",
                    subtitle = "Download finished — tap to install",
                    onClick = {
                        container.installIntent(pendingInstall.tag)?.let { context.startActivity(it) }
                    },
                )
            }
        }
        item {
            SettingRow(
                title = "Auto-check on launch",
                trailing = {
                    Switch(
                        checked = autoCheck,
                        onCheckedChange = { autoCheck = it; container.prefs.updateAutoCheck = it },
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

@Composable
private fun produceCacheUsage(): androidx.compose.runtime.State<Long?> {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.produceState<Long?>(null) {
        value = withContext(Dispatchers.IO) {
            File(context.filesDir, "stream_cache").walkBottomUp()
                .filter { it.isFile }.sumOf { it.length() }
        }
    }
}

@Composable
private fun MetadataSection(container: AppContainer) {
    val context = LocalContext.current
    var metaHours by remember { mutableIntStateOf(container.prefs.metaIntervalHours) }
    var metaWifiOnly by remember { mutableStateOf(container.prefs.metaWifiOnly) }
    var metaArtPrewarm by remember { mutableStateOf(container.prefs.metaArtPrewarm) }
    var metaCacheMb by remember { mutableIntStateOf(container.prefs.metaCacheMb) }

    // Live coverage: lyrics cached / total tracks / artist bios
    val coverage by produceState<Triple<Int, Int, Int>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                Triple(
                    container.database.lyricsDao().count(),
                    container.database.trackDao().count(),
                    container.database.artistInfoDao().count(),
                )
            }.getOrNull()
        }
    }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            "Pre-fetches lyrics, artist info and covers so everything works offline.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Off", 6 to "6h", 12 to "12h", 24 to "24h", 48 to "48h").forEach { (h, label) ->
                FilterChip(
                    selected = metaHours == h,
                    onClick = {
                        metaHours = h
                        container.prefs.metaIntervalHours = h
                        com.cadence.music.data.metadata.MetadataSync.schedule(context)
                    },
                    label = { Text(label) },
                )
            }
        }
        SettingRow(
            title = "Wi-Fi only",
            subtitle = "Auto-download only on unmetered networks",
            trailing = {
                Switch(
                    checked = metaWifiOnly,
                    onCheckedChange = {
                        metaWifiOnly = it
                        container.prefs.metaWifiOnly = it
                        com.cadence.music.data.metadata.MetadataSync.schedule(context)
                    },
                )
            },
        )
        SettingRow(
            title = "Pre-warm album art",
            subtitle = "Download server covers into the image cache",
            trailing = {
                Switch(
                    checked = metaArtPrewarm,
                    onCheckedChange = { metaArtPrewarm = it; container.prefs.metaArtPrewarm = it },
                )
            },
        )
        Text(
            "Image cache: $metaCacheMb MB — applies after restart",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Slider(
            value = metaCacheMb.toFloat(),
            onValueChange = { metaCacheMb = (it / 50).toInt() * 50 },
            onValueChangeFinished = { container.prefs.metaCacheMb = metaCacheMb },
            valueRange = 50f..1000f,
        )
        coverage?.let { (lyricsDone, trackTotal, bios) ->
            Text(
                "Lyrics $lyricsDone of $trackTotal tracks · $bios artists",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = { com.cadence.music.data.metadata.MetadataSync.runNow(context) },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Run now") }
    }
}
