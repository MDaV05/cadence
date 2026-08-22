package com.cadence.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cadence.music.AppContainer
import com.cadence.music.data.prefs.LibraryMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
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

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Library mode", style = MaterialTheme.typography.titleMedium)
            LibraryMode.entries.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == m, onClick = {
                        mode = m
                        container.prefs.mode = m
                    })
                    Text(
                        when (m) {
                            LibraryMode.LOCAL_ONLY -> "Local files only"
                            LibraryMode.API_ONLY -> "Server (API) only"
                            LibraryMode.HYBRID -> "Local files + server"
                        }
                    )
                }
            }

            HorizontalDivider()

            Text("Subsonic / Navidrome server", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(url, { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())

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
                ) { Text("Rescan library") }
            }

            if (busy) CircularProgressIndicator()
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()

            Text("Downloads", style = MaterialTheme.typography.titleMedium)
            Text("Format", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("raw" to "Original", "opus" to "Opus", "mp3" to "MP3").forEach { (f, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = dlFormat == f,
                        onClick = {
                            dlFormat = f
                            container.prefs.downloadFormat = f
                        },
                        label = { Text(label) },
                    )
                }
            }
            if (dlFormat != "raw") {
                Text("Bitrate: ${dlBitrate} kbps", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = dlBitrate.toFloat(),
                    onValueChange = { dlBitrate = it.toInt() },
                    onValueChangeFinished = { container.prefs.downloadBitrate = dlBitrate },
                    valueRange = 64f..320f,
                )
            }

            HorizontalDivider()

            Text("Stream cache: ${cacheGb} GB", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = cacheGb.toFloat(),
                onValueChange = { cacheGb = it.toInt() },
                onValueChangeFinished = {
                    container.prefs.cacheGb = cacheGb
                    status = "Cache cap applies after app restart"
                },
                valueRange = 1f..8f,
                steps = 6,
            )
        }
    }
}
