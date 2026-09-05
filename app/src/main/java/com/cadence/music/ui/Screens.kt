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
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cadence.music.AppContainer
import com.cadence.music.data.db.CustomThemeEntity
import com.cadence.music.data.prefs.LibraryMode
import com.cadence.music.data.prefs.Prefs
import com.cadence.music.data.prefs.ServerEntry
import com.cadence.music.data.prefs.ServerType
import com.cadence.music.data.source.EmbySource
import com.cadence.music.data.source.JellyfinSource
import com.cadence.music.data.source.PlexPin
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
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 16.dp) {
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
    var servers by remember { mutableStateOf(container.prefs.servers) }
    var showPicker by remember { mutableStateOf(false) }
    var addType by remember { mutableStateOf<ServerType?>(null) }
    var confirmDelete by remember { mutableStateOf<ServerEntry?>(null) }
    var editTarget by remember { mutableStateOf<ServerEntry?>(null) }
    var mode by remember { mutableStateOf(container.prefs.mode) }
    var status by remember { mutableStateOf("") }
    val syncErrors by container.library.lastSyncError.collectAsStateWithLifecycle()

    fun refresh() { servers = container.prefs.servers }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Servers") }
        items(servers, key = { it.id }) { e ->
            val failed = syncErrors.containsKey(e.id)
            SettingRow(
                title = "${e.type.name.lowercase().replaceFirstChar { it.uppercase() }} • ${e.url}",
                subtitle = if (failed) "Sync failed — tap to edit" else if (e.active) "Active" else "Disabled",
                onClick = if (failed) ({ editTarget = e }) else null,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = e.active,
                            onCheckedChange = { checked ->
                                container.prefs.servers = container.prefs.servers.map {
                                    if (it.id == e.id) it.copy(active = checked) else it
                                }
                                refresh()
                                scope.launch { runCatching { container.library.syncAll() } }
                            },
                        )
                        IconButton(onClick = { confirmDelete = e }) {
                            Icon(Icons.Filled.Delete, "Remove server")
                        }
                    }
                },
            )
        }
        item {
            TextButton(
                onClick = { showPicker = true },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) { Text("+ Add server") }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            status = ""
                            scope.launch {
                                runCatching { container.library.syncAll() }
                                    .onSuccess { status = "Library synced" }
                                    .onFailure { status = "Sync error: ${it.message}" }
                            }
                        },
                    ) { Text("Rescan") }
                }
                if (status.isNotEmpty()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
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

    if (showPicker) {
        ServerTypePicker(
            onPick = { t -> showPicker = false; addType = t },
            onDismiss = { showPicker = false },
        )
    }
    addType?.let { t ->
        AddServerSheet(
            container = container,
            type = t,
            onSaved = { refresh() },
            onDismiss = { addType = null },
        )
    }
    editTarget?.let { target ->
        AddServerSheet(
            container = container,
            type = target.type,
            existing = target,
            onSaved = { refresh() },
            onDismiss = { editTarget = null },
        )
    }
    confirmDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove server?") },
            text = { Text("Its tracks and downloads leave the library on the next sync.") },
            confirmButton = {
                TextButton(onClick = {
                    container.prefs.servers = container.prefs.servers.filter { it.id != e.id }
                    confirmDelete = null
                    refresh()
                    scope.launch { runCatching { container.library.syncAll() } }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ServerTypePicker(onPick: (ServerType) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add server") },
        text = {
            Column {
                listOf(
                    ServerType.SUBSONIC to "Navidrome, Gonic…",
                    ServerType.JELLYFIN to "Jellyfin servers",
                    ServerType.EMBY to "Emby servers",
                    ServerType.PLEX to "plex.tv login",
                ).forEach { (t, subtitle) ->
                    SettingRow(
                        title = t.name.lowercase().replaceFirstChar { it.uppercase() },
                        subtitle = subtitle,
                        onClick = { onPick(t) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun normalizeServerUrl(raw: String): String =
    raw.trim().let { if (it.contains("://")) it else "http://$it" }

private fun newServerId(): String = java.util.UUID.randomUUID().toString().take(8)

@Composable
private fun AddServerSheet(
    container: AppContainer,
    type: ServerType,
    existing: ServerEntry? = null,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val deviceId = remember {
        runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ANDROID_ID,
            )
        }.getOrNull() ?: "cadence"
    }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var pass by remember { mutableStateOf(existing?.password ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    // Plex PIN flow state: 0 = connect button, 1 = waiting for approval, 2 = pick server.
    // Editing a Plex entry jumps to 2 (URL edit; token preserved).
    var plexPhase by remember { mutableStateOf(if (existing?.type == ServerType.PLEX) 2 else 0) }
    var plexCode by remember { mutableStateOf("") }
    var plexToken by remember { mutableStateOf<String?>(null) }
    var plexOptions by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var plexPolling by remember { mutableStateOf(false) }

    fun saveAndSync(entry: ServerEntry) {
        // Same id = update in place, never a duplicate row.
        val cur = container.prefs.servers
        container.prefs.servers =
            if (cur.any { it.id == entry.id }) cur.map { if (it.id == entry.id) entry else it }
            else cur + entry
        onSaved()
        onDismiss()
        scope.launch { runCatching { container.library.syncAll() } }
    }

    suspend fun saveTyped() {
        // Scheme-less URLs ("192.168.1.106:4533") would fail silently on Android 9+.
        val candidate = ServerEntry(
            id = existing?.id ?: newServerId(), type = type,
            url = normalizeServerUrl(url), user = user.trim(), password = pass,
        )
        when (type) {
            ServerType.SUBSONIC -> {
                if (container.library.pingEntry(candidate)) saveAndSync(candidate)
                else error = "Couldn't connect — check URL and credentials."
            }
            ServerType.JELLYFIN, ServerType.EMBY -> {
                val authed = if (type == ServerType.JELLYFIN) {
                    JellyfinSource(candidate, deviceId).authenticate()
                } else {
                    EmbySource(candidate, deviceId).authenticate()
                }
                if (authed != null) {
                    saveAndSync(candidate.copy(token = authed.first, userId = authed.second, password = null))
                } else {
                    error = "Couldn't connect — check URL and credentials."
                }
            }
            ServerType.PLEX -> error = "Couldn't connect — check URL and credentials."
        }
    }

    fun startPlexPin() {
        busy = true; error = ""; plexPolling = true
        scope.launch {
            val pin = plexRequestPin(deviceId)
            if (pin == null || !plexPolling) {
                busy = false
                if (plexPolling) error = "Couldn't connect — check URL and credentials."
                return@launch
            }
            plexCode = pin.second; plexPhase = 1
            val deadline = System.currentTimeMillis() + 120_000
            var token: String? = null
            while (System.currentTimeMillis() < deadline && plexPolling) {
                kotlinx.coroutines.delay(2_000)
                token = plexPollToken(pin.first, deviceId)
                if (token != null) break
            }
            busy = false
            if (!plexPolling) return@launch
            if (token == null) {
                error = "Timed out waiting for approval — try again."
                return@launch
            }
            plexToken = token
            plexOptions = plexFetchServers(token, deviceId)
            if (plexOptions.isNotEmpty()) url = plexOptions.first().second
            plexPhase = 2
        }
    }

    AlertDialog(
        onDismissRequest = { plexPolling = false; onDismiss() },
        title = { Text(type.name.lowercase().replaceFirstChar { it.uppercase() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (type == ServerType.PLEX) {
                    when (plexPhase) {
                        0 -> Text("Sign in with your Plex account, then pick this device's server.")
                        1 -> {
                            Text("Enter this code at plex.tv/link:")
                            Text(plexCode, style = MaterialTheme.typography.headlineMedium)
                            TextButton(onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://plex.tv/link")),
                                )
                            }) { Text("Approve at plex.tv/link") }
                        }
                        else -> {
                            plexOptions.forEach { (name, uri) ->
                                SettingRow(
                                    title = name,
                                    subtitle = uri,
                                    trailing = {
                                        RadioButton(selected = url == uri, onClick = { url = uri })
                                    },
                                    onClick = { url = uri },
                                )
                            }
                            OutlinedTextField(
                                url, { url = it },
                                label = { Text("Server URL") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                    }
                } else {
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
                if (busy) CircularProgressIndicator()
                if (error.isNotEmpty()) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when {
                type != ServerType.PLEX -> TextButton(
                    enabled = !busy && url.isNotBlank() && user.isNotBlank(),
                    onClick = {
                        busy = true; error = ""
                        scope.launch {
                            try { saveTyped() } catch (e: Exception) {
                                error = "Error: ${e.message}"
                            }
                            busy = false
                        }
                    },
                ) { Text("Save & test") }
                plexPhase == 0 -> TextButton(enabled = !busy, onClick = { startPlexPin() }) {
                    Text("Connect with Plex")
                }
                plexPhase == 2 -> TextButton(
                    enabled = !busy && url.isNotBlank() && (plexToken != null || existing?.token != null),
                    onClick = {
                        val name = plexOptions.firstOrNull { it.second == url }?.first
                            ?: existing?.user ?: "Plex"
                        val candidate = ServerEntry(
                            id = existing?.id ?: newServerId(), type = ServerType.PLEX,
                            url = normalizeServerUrl(url), user = name,
                            token = plexToken ?: existing?.token,
                        )
                        busy = true; error = ""
                        scope.launch {
                            if (container.library.pingEntry(candidate)) saveAndSync(candidate)
                            else error = "Couldn't connect — check URL and credentials."
                            busy = false
                        }
                    },
                ) { Text("Save & test") }
                else -> TextButton(enabled = !busy, onClick = { plexPolling = false; onDismiss() }) {
                    Text("Cancel")
                }
            }
        },
        dismissButton = { TextButton(onClick = { plexPolling = false; onDismiss() }) { Text("Close") } },
    )
}

/** plex.tv client headers shared by the PIN flow. */
private fun plexClientHeaders(deviceId: String): Map<String, String> = mapOf(
    "X-Plex-Product" to "Cadence",
    "X-Plex-Client-Identifier" to deviceId,
    "X-Plex-Version" to "0.2.0",
    "Accept" to "application/json",
)

private suspend fun plexRequestPin(deviceId: String): Pair<Long, String>? =
    withContext(Dispatchers.IO) {
        runCatching {
            val conn = java.net.URL(PlexPin.requestUrl()).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.requestMethod = "POST"
            plexClientHeaders(deviceId).forEach { (k, v) -> conn.setRequestProperty(k, v) }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val obj = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                obj.getLong("id") to obj.getString("code")
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

/** One PIN poll; returns the auth token once the user approved, else null. */
private suspend fun plexPollToken(pinId: Long, deviceId: String): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val conn = java.net.URL(PlexPin.pollUrl(pinId)).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            plexClientHeaders(deviceId).forEach { (k, v) -> conn.setRequestProperty(k, v) }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                    .optString("authToken", null)?.ifBlank { null }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

/** Server list as (name, preferred uri); manual URL override always offered by the caller. */
private suspend fun plexFetchServers(token: String, deviceId: String): List<Pair<String, String>> =
    withContext(Dispatchers.IO) {
        runCatching {
            val conn = java.net.URL(PlexPin.resourcesUrl()).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            plexClientHeaders(deviceId).forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setRequestProperty("X-Plex-Token", token)
            try {
                if (conn.responseCode !in 200..299) return@runCatching emptyList()
                val arr = org.json.JSONArray(conn.inputStream.bufferedReader().readText())
                (0 until arr.length()).mapNotNull { i ->
                    val r = arr.optJSONObject(i) ?: return@mapNotNull null
                    val name = r.optString("name", "Plex server")
                    val conns = r.optJSONArray("connections") ?: return@mapNotNull null
                    var fallback: String? = null
                    var preferred: String? = null
                    for (j in 0 until conns.length()) {
                        val c = conns.optJSONObject(j) ?: continue
                        val uri = c.optString("uri", "").trimEnd('/')
                        if (uri.isBlank()) continue
                        fallback = fallback ?: uri
                        if (!c.optBoolean("relay", false)) preferred = preferred ?: uri
                    }
                    (preferred ?: fallback)?.let { name to it }
                }
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(emptyList())
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
    var enqueuedTag by remember { mutableStateOf<String?>(null) }
    var autoCheck by remember { mutableStateOf(container.prefs.updateAutoCheck) }
    var diag by remember { mutableStateOf("…") }
    LaunchedEffect(Unit) {
        diag = runCatching {
            withContext(Dispatchers.IO) {
                val db = container.database
                val tracks = db.trackDao().count()
                val albums = db.albumDao().count()
                val artists = db.trackDao().artistCount()
                val bytes = listOf(
                    File(container.filesDir(), "downloads"),
                    File(container.cacheDir(), "stream_cache"),
                    File(container.cacheDir(), "metadata_images"),
                ).sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
                "$tracks tracks • $albums albums • $artists artists • ${formatBytes(bytes)} on disk"
            }
        }.getOrDefault("Unavailable")
    }

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
                    else TextButton(onClick = { enqueuedTag = null; scope.launch { container.refreshUpdateStatus() } }) {
                        Text("Check now")
                    }
                },
                onClick = {
                    val u = update
                    if (u is Available) {
                        lastTapAvailable = u
                        if (enqueuedTag != u.tag) {
                            enqueuedTag = u.tag
                            runCatching { container.downloadUpdate(u.tag, u.assetUrl) }.onFailure {
                                enqueuedTag = null
                                Toast.makeText(context, "Couldn't start download", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        enqueuedTag = null
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
        item { SectionHeader("Support Cadence") }
        listOf(
            "BNB Smart Chain" to "0x57Ff65FB4b773F15BdfB507086facd28d8D7d049",
            "Bitcoin" to "bc1qeepyu36y79jw0nn4fyrkhppuppsdgvc6svxu36",
        ).forEach { (label, address) ->
            item {
                SettingRow(
                    title = label,
                    subtitle = address,
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Cadence $label address", address))
                        Toast.makeText(context, "$label address copied", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
        item { SectionHeader("Diagnostics") }
        item {
            SettingRow(
                title = "Database",
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
                                "Database: $diag\n" +
                                "Mode: ${container.prefs.mode}\n" +
                                "Servers: ${container.prefs.servers.count { it.active }} active of ${container.prefs.servers.size}"
                        }
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Cadence debug info", info))
                        Toast.makeText(context, "Debug info copied", Toast.LENGTH_SHORT).show()
                    }
                },
            )
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
