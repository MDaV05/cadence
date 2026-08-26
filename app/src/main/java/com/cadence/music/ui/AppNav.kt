package com.cadence.music.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cadence.music.AppContainer
import com.cadence.music.CadenceApp

@Composable
fun AppNav() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    val container = (LocalContext.current.applicationContext as CadenceApp).container

    Scaffold(
        bottomBar = {
            Column {
                val np by container.player.state.collectAsStateWithLifecycle()
                if (np.title.isNotEmpty()) {
                    androidx.compose.material3.Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clickable { navController.navigate("nowplaying") },
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.MusicNote,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(np.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    np.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            IconButton(onClick = { container.player.togglePlayPause() }) {
                                Icon(
                                    if (np.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    "Play/pause",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                if (current in listOf("home", "library", "playlists", "search", "settings")) {
                    NavigationBar {
                    NavigationBarItem(
                        selected = current == "home",
                        onClick = { navController.navigate("home") { launchSingleTop = true } },
                        icon = { Icon(Icons.Filled.Home, null) },
                        label = { Text("Home") },
                    )
                    NavigationBarItem(
                        selected = current == "library",
                        onClick = { navController.navigate("library") { launchSingleTop = true } },
                        icon = { Icon(Icons.Filled.LibraryMusic, null) },
                        label = { Text("Library") },
                    )
                    NavigationBarItem(
                        selected = current == "playlists",
                        onClick = { navController.navigate("playlists") { launchSingleTop = true } },
                        icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                        label = { Text("Playlists") },
                    )
                    NavigationBarItem(
                        selected = current == "search",
                        onClick = { navController.navigate("search") { launchSingleTop = true } },
                        icon = { Icon(Icons.Filled.Search, null) },
                        label = { Text("Search") },
                    )
                    NavigationBarItem(
                        selected = current == "settings",
                        onClick = { navController.navigate("settings") { launchSingleTop = true } },
                        icon = { Icon(Icons.Filled.Settings, null) },
                        label = { Text("Settings") },
                    )
                }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            composable("home") { HomeScreen(container, onArtistClick = { name ->
                navController.navigate("artist/${Uri.encode(name)}")
            }, onAlbumClick = { name ->
                navController.navigate("album/${Uri.encode(name)}")
            }) }
            composable("library") { LibraryScreen(container, onArtistClick = { name ->
                navController.navigate("artist/${Uri.encode(name)}")
            }, onAlbumClick = { name ->
                navController.navigate("album/${Uri.encode(name)}")
            }) }
            composable("playlists") {
                PlaylistsScreen(container, onOpen = { id ->
                    navController.navigate("playlist/$id")
                })
            }
            composable("playlist/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                PlaylistDetailScreen(container, id, onBack = { navController.popBackStack() })
            }
            composable("search") { SearchScreen(container) }
            composable("settings") { SettingsScreen(container, onOpenEqualizer = {
                navController.navigate("equalizer")
            }, onOpenDownloads = {
                navController.navigate("downloads")
            }) }
            composable("equalizer") {
                EqualizerScreen(container, onBack = { navController.popBackStack() })
            }
            composable("downloads") {
                DownloadsScreen(container, onBack = { navController.popBackStack() })
            }
            composable("nowplaying") { NowPlayingScreen(container) }
            composable("artist/{name}") { entry ->
                val name = entry.arguments?.getString("name") ?: return@composable
                ArtistScreen(
                    container,
                    java.net.URLDecoder.decode(name, "UTF-8"),
                    onAlbumClick = { album ->
                        navController.navigate("album/${Uri.encode(album)}")
                    },
                )
            }
            composable("album/{name}") { entry ->
                val name = entry.arguments?.getString("name") ?: return@composable
                AlbumScreen(container, java.net.URLDecoder.decode(name, "UTF-8"))
            }
        }
    }
}
