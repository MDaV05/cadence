package com.cadence.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cadence.music.AppContainer
import com.cadence.music.playback.EqManager

/** Dedicated full-screen panel for EQ bands and bass boost. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(container: AppContainer, onBack: () -> Unit = {}) {
    var enabled by remember { mutableStateOf(container.prefs.eqEnabled) }
    val bands = remember {
        val stored = container.prefs.eqBands.toMutableList()
        while (stored.size < EqManager.bandCount) stored.add(0)
        mutableStateListOf<Int>().apply { addAll(stored.take(EqManager.bandCount)) }
    }
    var bass by remember { mutableIntStateOf(container.prefs.eqBassBoost) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Equalizer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Enable equalizer", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Takes effect during playback",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                container.prefs.eqEnabled = it
                                EqManager.apply()
                            },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
            }

            if (!enabled) return@LazyColumn

            items(bands.size) { band ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(EqManager.centerFreqLabel(band) + " Hz", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "%+d dB".format(bands[band] / 100),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = bands[band].toFloat(),
                        onValueChange = {
                            bands[band] = it.toInt()
                            container.prefs.eqBands = bands.toList()
                            EqManager.apply()
                        },
                        valueRange = -1500f..1500f,
                    )
                }
            }

            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Bass boost: ${bass / 10}%", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = bass.toFloat(),
                        onValueChange = {
                            bass = it.toInt()
                            container.prefs.eqBassBoost = bass
                            EqManager.apply()
                        },
                        valueRange = 0f..1000f,
                    )
                }
            }
        }
    }
}
