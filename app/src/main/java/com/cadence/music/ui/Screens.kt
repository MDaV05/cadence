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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cadence.music.AppContainer
import com.cadence.music.data.SyncState
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
import kotlin.math.roundToInt

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
    initialTab: Int = 0,
    onOpenEqualizer: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
) {
    var tab by remember(initialTab) { mutableIntStateOf(initialTab) }
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
    var servers by remember { mutableStateOf(container.prefs.servers) }
    var showPicker by remember { mutableStateOf(false) }
    var addType by remember { mutableStateOf<ServerType?>(null) }
    var confirmDelete by remember { mutableStateOf<ServerEntry?>(null) }
    var editTarget by remember { mutableStateOf<ServerEntry?>(null) }
    var mode by remember { mutableStateOf(container.prefs.mode) }
    val syncErrors by container.library.lastSyncError.collectAsStateWithLifecycle()
    val syncState by container.library.syncState.collectAsStateWithLifecycle()

    fun refresh() { servers = container.prefs.servers }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Servers") }
        items(servers, key = { it.id }) { e ->
            val failed = syncErrors.containsKey(e.id)
            val serverTitle = if (e.customName?.isNotBlank() == true) {
                "${e.customName} (${e.type.name.lowercase()}) • ${e.url}"
            } else {
                "${e.type.name.lowercase().replaceFirstChar { it.uppercase() }} • ${e.url}"
            }
            val statusText = if (failed) "Sync failed" else if (e.active) "Active" else "Disabled"
            val subtitleText = if (!e.secondaryUrl.isNullOrBlank()) "$statusText • Alt: ${e.secondaryUrl}" else statusText
            SettingRow(
                title = serverTitle,
                subtitle = subtitleText,
                onClick = { editTarget = e },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = e.active,
                            onCheckedChange = { checked ->
                                container.prefs.servers = container.prefs.servers.map {
                                    if (it.id == e.id) it.copy(active = checked) else it
                                }
                                refresh()
                                container.library.launchSync()
                            },
                        )
                        IconButton(onClick = { editTarget = e }) {
                            Icon(Icons.Filled.Edit, "Edit server")
                        }
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
            SettingRow(
                title = "Show server name in stream tag",
                subtitle = "Display custom name in track tags (e.g. Stream (dav/jellyfin))",
                trailing = {
                    Switch(
                        checked = container.prefs.showServerNameInStreamTag,
                        onCheckedChange = {
                            container.prefs.showServerNameInStreamTag = it
                            refresh()
                        },
                    )
                },
            )
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { container.library.launchSync() },
                        enabled = syncState !is SyncState.Running,
                    ) { Text("Rescan") }
                    if (syncState is SyncState.Running) {
                        val running = syncState as SyncState.Running
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(
                            "Syncing ${running.doneAlbums}/${running.totalAlbums}…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                when (val st = syncState) {
                    is SyncState.Done -> Text(
                        "Library synced",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    is SyncState.Failed -> Text(
                        "Sync error: ${st.message}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    else -> {}
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
                        if (m != mode) { mode = m; container.prefs.mode = m; container.library.launchSync() }
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
                    container.library.launchSync()
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
                    ServerType.TELEGRAM to "Cloud music in chats/channels",
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
    var customName by remember { mutableStateOf(existing?.customName ?: "") }
    var secondaryUrl by remember { mutableStateOf(existing?.secondaryUrl ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    // Plex PIN flow state: 0 = connect button, 1 = waiting for approval, 2 = pick server.
    // Editing a Plex entry jumps to 2 (URL edit; token preserved).
    var plexPhase by remember { mutableStateOf(if (existing?.type == ServerType.PLEX) 2 else 0) }
    var plexCode by remember { mutableStateOf("") }
    var plexToken by remember { mutableStateOf<String?>(null) }
    var plexOptions by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var plexPolling by remember { mutableStateOf(false) }

    var tgAuthMethod by remember { mutableStateOf(if (existing?.token != null) 1 else 0) }
    var tgPhone by remember { mutableStateOf("") }
    var tgCode by remember { mutableStateOf("") }
    var tgPassword by remember { mutableStateOf("") }
    var tgBotToken by remember { mutableStateOf(existing?.token ?: "") }
    var tgChatTarget by remember { mutableStateOf(existing?.url ?: "me") }
    var tgDisplayName by remember { mutableStateOf(existing?.user ?: "Telegram Music") }
    val tgManager = remember { com.cadence.music.data.source.telegram.TelegramManager.get(context) }
    val tgState by tgManager.authState.collectAsStateWithLifecycle()
    val tgConn by tgManager.connectionState.collectAsStateWithLifecycle()
    var tgChats by remember { mutableStateOf<List<com.cadence.music.data.source.telegram.TelegramChatItem>>(emptyList()) }
    var tgChatsLoading by remember { mutableStateOf(false) }
    var tgSelectedChatIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var tgChatSearchQuery by remember { mutableStateOf("") }
    var tgManualChatMode by remember { mutableStateOf(false) }
    var tgShowProxy by remember { mutableStateOf(false) }
    var tgProxyHost by remember { mutableStateOf("127.0.0.1") }
    var tgProxyPort by remember { mutableStateOf("10808") }
    var tgProxyApplied by remember { mutableStateOf(false) }

    LaunchedEffect(tgState) {
        if (tgState is com.cadence.music.data.source.telegram.TelegramAuthState.Ready) {
            tgChatsLoading = true
            try {
                val loaded = tgManager.getAvailableMusicChats()
                tgChats = loaded
                if (existing != null && existing.type == ServerType.TELEGRAM) {
                    val parsed = com.cadence.music.data.source.telegram.parseTelegramChatIds(existing.url, tgManager.getMyUserId())
                    tgSelectedChatIds = parsed.toSet()
                } else if (tgSelectedChatIds.isEmpty()) {
                    val saved = loaded.firstOrNull { it.isSavedMessages }
                    if (saved != null) {
                        tgSelectedChatIds = setOf(saved.id)
                    }
                }
            } catch (_: Exception) {}
            tgChatsLoading = false
        }
    }

    fun saveAndSync(entry: ServerEntry) {
        // Same id = update in place, never a duplicate row.
        val cur = container.prefs.servers
        container.prefs.servers =
            if (cur.any { it.id == entry.id }) cur.map { if (it.id == entry.id) entry else it }
            else cur + entry
        onSaved()
        onDismiss()
        container.library.launchSync()
    }

    suspend fun saveTyped() {
        // Scheme-less URLs ("192.168.1.106:4533") would fail silently on Android 9+.
        val normSecondary = secondaryUrl.trim().ifBlank { null }?.let { normalizeServerUrl(it) }
        val candidate = ServerEntry(
            id = existing?.id ?: newServerId(), type = type,
            url = normalizeServerUrl(url), user = user.trim(),
            password = pass.ifBlank { existing?.password },
            token = existing?.token,
            userId = existing?.userId,
            customName = customName.trim().ifBlank { null },
            secondaryUrl = normSecondary,
        )
        when (type) {
            ServerType.SUBSONIC -> {
                if (container.library.pingEntry(candidate)) saveAndSync(candidate)
                else error = "Couldn't connect — check URL and credentials."
            }
            ServerType.JELLYFIN, ServerType.EMBY -> {
                if (pass.isBlank() && existing?.token != null && container.library.pingEntry(candidate)) {
                    saveAndSync(candidate)
                } else {
                    val authed = if (type == ServerType.JELLYFIN) {
                        JellyfinSource(candidate, deviceId).authenticate()
                            ?: candidate.secondaryUrl?.let { JellyfinSource(candidate.copy(url = it), deviceId).authenticate() }
                    } else {
                        EmbySource(candidate, deviceId).authenticate()
                            ?: candidate.secondaryUrl?.let { EmbySource(candidate.copy(url = it), deviceId).authenticate() }
                    }
                    if (authed != null) {
                        saveAndSync(candidate.copy(token = authed.first, userId = authed.second, password = null))
                    } else {
                        error = "Couldn't connect — check URL and credentials."
                    }
                }
            }
            ServerType.PLEX -> error = "Couldn't connect — check URL and credentials."
            ServerType.TELEGRAM -> saveAndSync(candidate)
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
                                value = customName,
                                onValueChange = { customName = it },
                                label = { Text("Server Name / Nickname (Optional)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                url, { url = it },
                                label = { Text("Server URL") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = secondaryUrl,
                                onValueChange = { secondaryUrl = it },
                                label = { Text("Secondary URL (Optional, e.g. LAN IP)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                    }
                } else if (type == ServerType.TELEGRAM) {
                    Text("Sync and stream audio directly from Telegram without saving files on your device.", style = MaterialTheme.typography.bodyMedium)

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = tgAuthMethod == 0,
                                onClick = { tgAuthMethod = 0 },
                                label = { Text("Account Login") },
                            )
                            FilterChip(
                                selected = tgAuthMethod == 1,
                                onClick = { tgAuthMethod = 1 },
                                label = { Text("Bot Token") },
                            )
                        }
                        TextButton(onClick = { tgShowProxy = !tgShowProxy }) {
                            Text(if (tgShowProxy) "Hide Proxy" else "Proxy", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Connection: $tgConn",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (tgConn == "Connected") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (tgShowProxy) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("SOCKS5 Proxy (v2rayNG / Clash / Nekobox)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = tgProxyHost,
                                        onValueChange = { tgProxyHost = it },
                                        label = { Text("Host") },
                                        modifier = Modifier.weight(2f),
                                        singleLine = true,
                                    )
                                    OutlinedTextField(
                                        value = tgProxyPort,
                                        onValueChange = { tgProxyPort = it },
                                        label = { Text("Port") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                        ),
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            val p = tgProxyPort.toIntOrNull() ?: 10808
                                            scope.launch {
                                                try {
                                                    tgManager.addSocks5Proxy(tgProxyHost, p)
                                                    tgProxyApplied = true
                                                    error = ""
                                                } catch (e: Exception) {
                                                    error = "Proxy error: ${e.message}"
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(if (tgProxyApplied) "Re-apply" else "Apply Proxy")
                                    }
                                    if (tgProxyApplied) {
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    tgManager.disableProxy()
                                                    tgProxyApplied = false
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text("Disable")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (tgAuthMethod == 0) {
                        when (val st = tgState) {
                            is com.cadence.music.data.source.telegram.TelegramAuthState.Ready -> {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("✓ Connected to Telegram", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                                    TextButton(onClick = {
                                        scope.launch {
                                            tgManager.logOut()
                                            tgChats = emptyList()
                                            tgSelectedChatIds = emptySet()
                                        }
                                    }) {
                                        Text("Log out", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                    }
                                }

                                if (tgChatsLoading) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        Spacer(Modifier.size(8.dp))
                                        Text("Loading chats...", style = MaterialTheme.typography.bodySmall)
                                    }
                                } else if (tgChats.isNotEmpty() && !tgManualChatMode) {
                                    Text(
                                        "Select chats/channels to sync into your library:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )

                                    OutlinedTextField(
                                        value = tgChatSearchQuery,
                                        onValueChange = { tgChatSearchQuery = it },
                                        placeholder = { Text("Search chats...") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                    )

                                    val filteredChats = if (tgChatSearchQuery.isBlank()) tgChats else tgChats.filter {
                                        it.title.contains(tgChatSearchQuery, ignoreCase = true) || it.typeName.contains(tgChatSearchQuery, ignoreCase = true)
                                    }

                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "${tgSelectedChatIds.size} of ${tgChats.size} selected",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Row {
                                            TextButton(onClick = {
                                                tgSelectedChatIds = filteredChats.map { it.id }.toSet()
                                            }) {
                                                Text("Select all", style = MaterialTheme.typography.labelSmall)
                                            }
                                            TextButton(onClick = { tgSelectedChatIds = emptySet() }) {
                                                Text("Clear", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }

                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 180.dp)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        for (chat in filteredChats) {
                                            val isChecked = tgSelectedChatIds.contains(chat.id)
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        tgSelectedChatIds = if (isChecked) {
                                                            tgSelectedChatIds - chat.id
                                                        } else {
                                                            tgSelectedChatIds + chat.id
                                                        }
                                                    }
                                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Checkbox(
                                                    checked = isChecked,
                                                    onCheckedChange = { checked ->
                                                        tgSelectedChatIds = if (checked) {
                                                            tgSelectedChatIds + chat.id
                                                        } else {
                                                            tgSelectedChatIds - chat.id
                                                        }
                                                    },
                                                )
                                                Spacer(Modifier.size(4.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(
                                                        chat.title,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Medium,
                                                    )
                                                    Text(
                                                        chat.typeName,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    TextButton(
                                        onClick = { tgManualChatMode = true },
                                        modifier = Modifier.align(Alignment.End),
                                    ) {
                                        Text("Manual chat ID", style = MaterialTheme.typography.labelSmall)
                                    }
                                } else {
                                    OutlinedTextField(
                                        value = tgChatTarget,
                                        onValueChange = { tgChatTarget = it },
                                        label = { Text("Chat IDs (comma-separated or 'me')") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                    )
                                    if (tgChats.isNotEmpty()) {
                                        TextButton(
                                            onClick = { tgManualChatMode = false },
                                            modifier = Modifier.align(Alignment.End),
                                        ) {
                                            Text("Show chat list", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = tgDisplayName,
                                    onValueChange = { tgDisplayName = it },
                                    label = { Text("Display Name") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                            }
                            is com.cadence.music.data.source.telegram.TelegramAuthState.WaitCode -> {
                                Text("Enter the login code:", fontWeight = FontWeight.SemiBold)
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "Telegram sends login codes to your official Telegram app (check the 'Telegram' Service Notifications chat in your Telegram mobile/desktop app), NOT via SMS.",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(10.dp),
                                    )
                                }
                                OutlinedTextField(
                                    value = tgCode,
                                    onValueChange = { tgCode = it },
                                    label = { Text("Login Code") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                    ),
                                )
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            busy = true; error = ""
                                            scope.launch {
                                                try {
                                                    tgManager.sendAuthCode(tgCode)
                                                } catch (e: Exception) {
                                                    error = e.message ?: "Failed to verify code"
                                                } finally {
                                                    busy = false
                                                }
                                            }
                                        },
                                        enabled = !busy && tgCode.isNotBlank(),
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Verify Code") }
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                tgManager.restart()
                                                tgCode = ""
                                            }
                                        },
                                        enabled = !busy,
                                    ) { Text("Back") }
                                }
                            }
                            is com.cadence.music.data.source.telegram.TelegramAuthState.WaitPassword -> {
                                Text("Enter your 2FA Cloud Password" + (st.hint?.let { " (Hint: $it)" } ?: "") + ":")
                                OutlinedTextField(
                                    value = tgPassword,
                                    onValueChange = { tgPassword = it },
                                    label = { Text("2FA Password") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                )
                                Button(
                                    onClick = {
                                        busy = true; error = ""
                                        scope.launch {
                                            try {
                                                tgManager.sendPassword(tgPassword)
                                            } catch (e: Exception) {
                                                error = e.message ?: "Incorrect 2FA password"
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    },
                                    enabled = !busy && tgPassword.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Submit Password") }
                            }
                            is com.cadence.music.data.source.telegram.TelegramAuthState.Error -> {
                                Text("Telegram Error", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                                Text(st.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                Button(
                                    onClick = {
                                        error = ""
                                        tgManager.restart()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Retry") }
                            }
                            else -> {
                                OutlinedTextField(
                                    value = tgPhone,
                                    onValueChange = { tgPhone = it },
                                    label = { Text("Phone number with country code") },
                                    placeholder = { Text("+1234567890 or +98912...") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone
                                    ),
                                )
                                Button(
                                    onClick = {
                                        busy = true; error = ""
                                        scope.launch {
                                            try {
                                                tgManager.sendPhoneNumber(tgPhone)
                                            } catch (e: Exception) {
                                                error = e.message ?: "Failed to send code"
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    },
                                    enabled = !busy && tgPhone.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Send Login Code")
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = tgBotToken,
                            onValueChange = { tgBotToken = it },
                            label = { Text("Bot Token (from @BotFather)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = tgChatTarget,
                            onValueChange = { tgChatTarget = it },
                            label = { Text("Channel / Chat ID (e.g. -100... or @channel)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = tgDisplayName,
                            onValueChange = { tgDisplayName = it },
                            label = { Text("Display Name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Button(
                            onClick = {
                                busy = true; error = ""
                                scope.launch {
                                    try {
                                        tgManager.sendBotToken(tgBotToken)
                                    } catch (e: Exception) {
                                        error = e.message ?: "Failed to connect bot"
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy && tgBotToken.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Connect Bot") }
                    }
                } else {
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("Server Name / Nickname (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(url, { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(
                        value = secondaryUrl,
                        onValueChange = { secondaryUrl = it },
                        label = { Text("Secondary URL (Optional, e.g. LAN IP)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(user, { user = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(
                        pass, { pass = it },
                        label = { Text(if (existing?.token != null) "Password (leave blank to keep current)" else "Password") },
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
                type == ServerType.TELEGRAM -> TextButton(
                    enabled = !busy && (
                        (tgAuthMethod == 0 && (tgSelectedChatIds.isNotEmpty() || tgChatTarget.isNotBlank())) ||
                        (tgAuthMethod == 1 && tgChatTarget.isNotBlank() && tgBotToken.isNotBlank())
                    ),
                    onClick = {
                        val targetUrl = if (tgAuthMethod == 0 && tgSelectedChatIds.isNotEmpty() && !tgManualChatMode) {
                            tgSelectedChatIds.joinToString(",")
                        } else {
                            tgChatTarget.trim().ifBlank { "me" }
                        }
                        val defaultName = if (tgAuthMethod == 0 && !tgManualChatMode && tgSelectedChatIds.isNotEmpty()) {
                            if (tgSelectedChatIds.size == 1) {
                                tgChats.find { it.id == tgSelectedChatIds.first() }?.title ?: "Telegram Music"
                            } else {
                                "Telegram Music (${tgSelectedChatIds.size} chats)"
                            }
                        } else {
                            "Telegram Music"
                        }
                        val resolvedCustomName = if (tgDisplayName.isNotBlank() && tgDisplayName != "Telegram Music") {
                            tgDisplayName.trim()
                        } else {
                            customName.trim().ifBlank { null }
                        }
                        val candidate = ServerEntry(
                            id = existing?.id ?: newServerId(),
                            type = ServerType.TELEGRAM,
                            url = targetUrl,
                            user = tgDisplayName.trim().ifBlank { defaultName },
                            token = if (tgAuthMethod == 1) tgBotToken.trim() else null,
                            userId = tgManager.myUserId?.toString(),
                            customName = resolvedCustomName,
                        )
                        saveAndSync(candidate)
                    },
                ) { Text("Save & sync") }
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
                        val normSecondary = secondaryUrl.trim().ifBlank { null }?.let { normalizeServerUrl(it) }
                        val candidate = ServerEntry(
                            id = existing?.id ?: newServerId(), type = ServerType.PLEX,
                            url = normalizeServerUrl(url), user = name,
                            token = plexToken ?: existing?.token,
                            customName = customName.trim().ifBlank { null },
                            secondaryUrl = normSecondary,
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
    val cacheUnlimited = remember { mutableStateOf(container.prefs.cacheUnlimited) }
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
            CacheLimit(
                title = "Size limit",
                gb = cacheGb.value,
                unlimited = cacheUnlimited.value,
                subtitle = cacheUsage?.let { used -> "Currently using ${"%.1f".format(used / (1024f * 1024 * 1024))} GB — applies after restart" },
                onGb = {
                    cacheGb.value = it
                    container.prefs.cacheGb = it
                },
                onUnlimited = {
                    cacheUnlimited.value = it
                    container.prefs.cacheUnlimited = it
                },
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

/** Slider 1-100 GB with an Unlimited switch; commits the limit only when the drag ends. */
@Composable
private fun CacheLimit(
    title: String,
    gb: Int,
    unlimited: Boolean,
    subtitle: String?,
    modifier: Modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    onGb: (Int) -> Unit,
    onUnlimited: (Boolean) -> Unit,
) {
    // Local drag value keeps the slider/label responsive without per-tick pref writes.
    var value by remember(gb) { mutableFloatStateOf(gb.toFloat()) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (unlimited) "$title: Unlimited" else "$title: ${value.roundToInt()} GB",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = unlimited, onCheckedChange = onUnlimited)
            Text("Unlimited", style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onGb(value.roundToInt().coerceIn(1, 100)) },
            valueRange = 1f..100f,
            enabled = !unlimited,
        )
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        if (com.cadence.music.BuildConfig.ENABLE_UPDATER) {
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
        } else {
            item {
                SettingRow(
                    title = "Updates",
                    subtitle = "Managed by F-Droid",
                )
            }
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
    var imageCacheGb by remember { mutableIntStateOf(container.prefs.imageCacheGb) }
    var imageUnlimited by remember { mutableStateOf(container.prefs.imageUnlimited) }

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
        CacheLimit(
            title = "Image cache",
            gb = imageCacheGb,
            unlimited = imageUnlimited,
            subtitle = "Applies after restart",
            modifier = Modifier.padding(vertical = 8.dp),
            onGb = {
                imageCacheGb = it
                container.prefs.imageCacheGb = it
            },
            onUnlimited = {
                imageUnlimited = it
                container.prefs.imageUnlimited = it
            },
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
