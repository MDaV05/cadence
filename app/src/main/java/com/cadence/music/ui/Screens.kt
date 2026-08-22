package com.cadence.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cadence.music.AppContainer
import com.cadence.music.data.prefs.LibraryMode
import com.cadence.music.data.prefs.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
private fun SectionHeader(text: String) {
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
private fun SettingRow(title: String, subtitle: String? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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
fun SettingsScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val server = container.prefs.server

    var url by remember { mutableStateOf(server?.url ?: "") }
    var user by remember { mutableStateOf(server?.user ?: "") }
    var pass by remember { mutableStateOf(server?.password ?: "") }
    var mode by remember { mutableStateOf(container.prefs.mode) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var dlFormat by remember { mutableStateOf(container.prefs.downloadFormat) }
    var dlBitrate by remember { mutableIntStateOf(container.prefs.downloadBitrate) }
    var cacheGb by remember { mutableIntStateOf(container.prefs.cacheGb) }
    var lbToken by remember { mutableStateOf(container.prefs.listenBrainzToken ?: "") }
    var gesture by remember { mutableStateOf(container.prefs.trackGesture) }

    val cacheUsage by produceCacheUsage()

    Scaffold { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {

            item { SectionHeader("Server") }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(url, { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(user, { user = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
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
                                        container.prefs.server =
                                            com.cadence.music.data.prefs.ServerConfig(url, user, pass)
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

            item { HorizontalDivider() }
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
                            mode = m
                            container.prefs.mode = m
                        })
                    },
                )
            }

            item { HorizontalDivider() }
            item { SectionHeader("Downloads") }
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("raw" to "Original", "opus" to "Opus", "mp3" to "MP3").forEach { (f, label) ->
                        FilterChip(
                            selected = dlFormat == f,
                            onClick = { dlFormat = f; container.prefs.downloadFormat = f },
                            label = { Text(label) },
                        )
                    }
                }
            }
            if (dlFormat != "raw") {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Bitrate: ${dlBitrate} kbps", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = dlBitrate.toFloat(),
                            onValueChange = { dlBitrate = it.toInt() },
                            onValueChangeFinished = { container.prefs.downloadBitrate = dlBitrate },
                            valueRange = 64f..320f,
                        )
                    }
                }
            }

            item { HorizontalDivider() }
            item { SectionHeader("Stream cache") }
            item {
                SettingRow(
                    title = "Size limit: ${cacheGb} GB",
                    subtitle = cacheUsage?.let { used -> "Currently using ${"%.1f".format(used / (1024f * 1024 * 1024))} GB — applies after restart" },
                )
            }
            item {
                Slider(
                    value = cacheGb.toFloat(),
                    onValueChange = { cacheGb = it.toInt() },
                    onValueChangeFinished = { container.prefs.cacheGb = cacheGb },
                    valueRange = 1f..8f,
                    steps = 6,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            item { HorizontalDivider() }
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
                        selected = gesture == Prefs.TrackGesture.HORIZONTAL,
                        onClick = { gesture = Prefs.TrackGesture.HORIZONTAL; container.prefs.trackGesture = Prefs.TrackGesture.HORIZONTAL },
                        label = { Text("Swipe left/right") },
                    )
                    FilterChip(
                        selected = gesture == Prefs.TrackGesture.VERTICAL,
                        onClick = { gesture = Prefs.TrackGesture.VERTICAL; container.prefs.trackGesture = Prefs.TrackGesture.VERTICAL },
                        label = { Text("Swipe up/down") },
                    )
                }
            }

            item { HorizontalDivider() }
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
