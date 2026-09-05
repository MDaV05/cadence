package com.cadence.music.ui

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun AppNav(initialSettingsTab: Int = 0, onDeepLinkConsumed: () -> Unit = {}) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    val container = (LocalContext.current.applicationContext as CadenceApp).container

    // Sticky: MainActivity clears its flag via onDeepLinkConsumed, which would flip
    // SettingsScreen's keyed remember(initialTab) back to 0 mid-visit. Holding the
    // applied tab here keeps the in-flight visit stable; it is dropped on leaving
    // Settings so later manual visits open the default tab.
    var deepLinkTab by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(initialSettingsTab) {
        if (initialSettingsTab != 0) {
            deepLinkTab = initialSettingsTab
            navController.navigate("settings") { launchSingleTop = true }
            onDeepLinkConsumed()
        }
    }
    androidx.compose.runtime.LaunchedEffect(current) {
        if (current != null && current != "settings") deepLinkTab = 0
    }

    Scaffold(
        bottomBar = {
            Column {
                val np by container.player.state.collectAsStateWithLifecycle()
                // Hidden on now-playing — the full screen already shows the track.
                if (np.title.isNotEmpty() && current != "nowplaying") {
                    val queue by container.player.queueItems.collectAsStateWithLifecycle()
                    val queueIdx by container.player.queueIndexFlow.collectAsStateWithLifecycle()
                    androidx.compose.material3.Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clickable(
                                role = androidx.compose.ui.semantics.Role.Button,
                                onClickLabel = "Open now playing",
                                onClick = { navController.navigate("nowplaying") { launchSingleTop = true } },
                            ),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TrackArt(
                                container,
                                queue.getOrNull(queueIdx)?.mediaId,
                                Modifier
                                    .padding(vertical = 8.dp)
                                    .size(40.dp),
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
                            Crossfade(targetState = np.isPlaying, label = "miniPlay") { playing ->
                                IconButton(onClick = { container.player.togglePlayPause() }) {
                                    Icon(
                                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        "Play/pause",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
                if (current in listOf("home", "library", "playlists", "search", "settings")) {
                    NavigationBar {
                    NavigationBarItem(
                        selected = current == "home",
                        onClick = { navController.navigate("home") { launchSingleTop = true; popUpTo(navController.graph.startDestinationId) { saveState = true }; restoreState = true } },
                        icon = { Icon(if (current == "home") Icons.Filled.Home else Icons.Outlined.Home, null) },
                        label = { Text("Home") },
                    )
                    NavigationBarItem(
                        selected = current == "library",
                        onClick = { navController.navigate("library") { launchSingleTop = true; popUpTo(navController.graph.startDestinationId) { saveState = true }; restoreState = true } },
                        icon = { Icon(if (current == "library") Icons.Filled.LibraryMusic else Icons.Outlined.LibraryMusic, null) },
                        label = { Text("Library") },
                    )
                    NavigationBarItem(
                        selected = current == "playlists",
                        onClick = { navController.navigate("playlists") { launchSingleTop = true; popUpTo(navController.graph.startDestinationId) { saveState = true }; restoreState = true } },
                        icon = { Icon(if (current == "playlists") Icons.AutoMirrored.Filled.QueueMusic else Icons.AutoMirrored.Outlined.QueueMusic, null) },
                        label = { Text("Playlists") },
                    )
                    NavigationBarItem(
                        selected = current == "search",
                        onClick = { navController.navigate("search") { launchSingleTop = true; popUpTo(navController.graph.startDestinationId) { saveState = true }; restoreState = true } },
                        icon = { Icon(if (current == "search") Icons.Filled.Search else Icons.Outlined.Search, null) },
                        label = { Text("Search") },
                    )
                    NavigationBarItem(
                        selected = current == "settings",
                        onClick = { navController.navigate("settings") { launchSingleTop = true; popUpTo(navController.graph.startDestinationId) { saveState = true }; restoreState = true } },
                        icon = { Icon(if (current == "settings") Icons.Filled.Settings else Icons.Outlined.Settings, null) },
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
            }, onOpenLibrary = {
                navController.navigate("library") { launchSingleTop = true; popUpTo(navController.graph.startDestinationId) { saveState = true }; restoreState = true }
            }, onOpenPlaylists = {
                navController.navigate("playlists") { launchSingleTop = true; popUpTo(navController.graph.startDestinationId) { saveState = true }; restoreState = true }
            }, onOpenSearch = {
                navController.navigate("search") { launchSingleTop = true; popUpTo(navController.graph.startDestinationId) { saveState = true }; restoreState = true }
            }, onOpenDownloads = {
                navController.navigate("downloads") { launchSingleTop = true; popUpTo(navController.graph.startDestinationId) { saveState = true }; restoreState = true }
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
            composable("search") { SearchScreen(container, onArtistClick = { name ->
                navController.navigate("artist/${Uri.encode(name)}")
            }) }
            composable("settings") { SettingsScreen(container, initialTab = deepLinkTab, onOpenEqualizer = {
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
