package com.cadence.music.ui

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cadence.music.AppContainer
import com.cadence.music.CadenceApp
import com.cadence.music.data.update.UpdateStatus

@Composable
fun AppNav(initialSettingsTab: Int = 0, onDeepLinkConsumed: () -> Unit = {}) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    val container = (LocalContext.current.applicationContext as CadenceApp).container

    if (com.cadence.music.BuildConfig.ENABLE_UPDATER) {
        UpdatePopup(container)
    }

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

    // One-shot hint: the Home "Playlists" quick tile wants Library to open on its
    // Playlists tab. Set right before navigating, consumed by LibraryScreen's
    // initialTab, cleared once Library is left — mirroring deepLinkTab above.
    var libraryStartTab by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(current) {
        if (current != null && current != "library") libraryStartTab = 0
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            val hasNavBar = current in listOf("home", "library", "stats", "search", "settings")
            Column(
                modifier = if (!hasNavBar) Modifier.navigationBarsPadding() else Modifier,
            ) {
                val np by container.player.state.collectAsStateWithLifecycle()
                val skinLayout = com.cadence.music.ui.theme.LocalSkin.current.layout
                // Hidden on now-playing — the full screen already shows the track.
                if (np.title.isNotEmpty() && current != "nowplaying") {
                    MiniPlayer(
                        container = container,
                        np = np,
                        layout = skinLayout,
                        onOpenNowPlaying = { navController.navigate("nowplaying") { launchSingleTop = true } },
                    )
                }
                if (hasNavBar) {
                    val tabs = listOf(
                        NavDest("home", "Home", Icons.Outlined.Home, Icons.Filled.Home),
                        NavDest("library", "Library", Icons.Outlined.LibraryMusic, Icons.Filled.LibraryMusic),
                        NavDest("stats", "Stats", Icons.Outlined.Insights, Icons.Filled.Insights),
                        NavDest("search", "Search", Icons.Outlined.Search, Icons.Filled.Search),
                        NavDest("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
                    )
                    when (skinLayout) {
                        com.cadence.music.ui.theme.SkinLayout.TURNTABLE -> {
                            FloatingPillNav(current, tabs) { route ->
                                navController.navigate(route) { launchSingleTop = true; popUpTo(navController.graph.startDestinationId) { saveState = true }; restoreState = true }
                            }
                        }
                        com.cadence.music.ui.theme.SkinLayout.SPOTIFY -> {
                            val itemColors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = Color.Transparent,
                                unselectedIconColor = Color(0xFFB3B3B3),
                                unselectedTextColor = Color(0xFFB3B3B3),
                            )
                            NavigationBar(
                                containerColor = Color(0xFF121212),
                                tonalElevation = 0.dp,
                            ) {
                                tabs.forEach { tab ->
                                    NavigationBarItem(
                                        selected = current == tab.route,
                                        onClick = {
                                            navController.navigate(tab.route) { launchSingleTop = true; popUpTo(navController.graph.startDestinationId) { saveState = true }; restoreState = true }
                                        },
                                        icon = { Icon(if (current == tab.route) tab.filled else tab.outlined, null) },
                                        label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                                        colors = itemColors,
                                    )
                                }
                            }
                        }
                        com.cadence.music.ui.theme.SkinLayout.APPLE -> {
                            val itemColors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = Color.Transparent,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                                tonalElevation = 0.dp,
                            ) {
                                tabs.forEach { tab ->
                                    NavigationBarItem(
                                        selected = current == tab.route,
                                        onClick = {
                                            navController.navigate(tab.route) { launchSingleTop = true; popUpTo(navController.graph.startDestinationId) { saveState = true }; restoreState = true }
                                        },
                                        icon = { Icon(if (current == tab.route) tab.filled else tab.outlined, null) },
                                        label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                                        colors = itemColors,
                                    )
                                }
                            }
                        }
                        com.cadence.music.ui.theme.SkinLayout.STANDARD -> {
                            val itemColors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.background,
                                tonalElevation = 0.dp,
                            ) {
                                tabs.forEach { tab ->
                                    NavigationBarItem(
                                        selected = current == tab.route,
                                        onClick = {
                                            navController.navigate(tab.route) { launchSingleTop = true; popUpTo(navController.graph.startDestinationId) { saveState = true }; restoreState = true }
                                        },
                                        icon = { Icon(if (current == tab.route) tab.filled else tab.outlined, null) },
                                        label = { Text(tab.label) },
                                        colors = itemColors,
                                    )
                                }
                            }
                        }
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
                .padding(padding)
                // The outer Scaffold already applied the bottom-bar inset; without
                // this, inner Scaffolds re-apply it and every tab list ends in
                // dead space above the nav bar (audit F11).
                .consumeWindowInsets(padding),
        ) {
            composable("home") { HomeScreen(container, onArtistClick = { name ->
                navController.navigate("artist/${Uri.encode(name)}")
            }, onAlbumClick = { name ->
                navController.navigate("album/${Uri.encode(name)}")
            }, onOpenLibrary = {
                navController.navigate("library") { launchSingleTop = true; popUpTo(navController.graph.startDestinationId) { saveState = true }; restoreState = true }
            }, onOpenPlaylists = {
                libraryStartTab = 3
                navController.navigate("library") { launchSingleTop = true; popUpTo(navController.graph.startDestinationId) { saveState = true }; restoreState = true }
            }, onOpenSearch = {
                navController.navigate("search") { launchSingleTop = true; popUpTo(navController.graph.startDestinationId) { saveState = true }; restoreState = true }
            }, onOpenDownloads = {
                navController.navigate("downloads") { launchSingleTop = true; popUpTo(navController.graph.startDestinationId) { saveState = true }; restoreState = true }
            }) }
            composable("library") { LibraryScreen(container, initialTab = libraryStartTab, onArtistClick = { name ->
                navController.navigate("artist/${Uri.encode(name)}")
            }, onAlbumClick = { name ->
                navController.navigate("album/${Uri.encode(name)}")
            }, onOpenPlaylist = { id ->
                navController.navigate("playlist/$id")
            }, onOpenLiked = {
                navController.navigate("liked")
            }) }
            composable("liked") { LikedSongsScreen(container, onArtistClick = { name ->
                navController.navigate("artist/${Uri.encode(name)}")
            }) }
            composable("stats") { StatsScreen(container, onArtistClick = { name ->
                navController.navigate("artist/${Uri.encode(name)}")
            }) }
            composable("playlist/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                PlaylistDetailScreen(container, id, onBack = { navController.popBackStack() })
            }
            composable("search") { SearchScreen(container, onArtistClick = { name ->
                navController.navigate("artist/${Uri.encode(name)}")
            }, onAlbumClick = { name ->
                navController.navigate("album/${Uri.encode(name)}")
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
                DownloadsScreen(
                    container,
                    onBack = { navController.popBackStack() },
                    onAlbumClick = { name ->
                        navController.navigate("album/${Uri.encode(name)}")
                    },
                )
            }
            composable("nowplaying") {
                NowPlayingScreen(
                    container,
                    onBack = { navController.popBackStack() },
                    onArtistClick = { name ->
                        navController.navigate("artist/${Uri.encode(name)}")
                    },
                    // Album route keys on albumNorm, not the display name.
                    onAlbumClick = { norm ->
                        navController.navigate("album/${Uri.encode(norm)}")
                    },
                )
            }
            composable("artist/{name}") { entry ->
                // NavController already decoded the arg; a second decode turns
                // "+" into a space and crashes on a trailing "%".
                val name = entry.arguments?.getString("name") ?: return@composable
                ArtistScreen(
                    container,
                    name,
                    onAlbumClick = { album ->
                        navController.navigate("album/${Uri.encode(album)}")
                    },
                    // launchSingleTop: the sheet's "go to" entries can target
                    // the artist already on screen; don't stack a duplicate.
                    onArtistClick = { artist ->
                        navController.navigate("artist/${Uri.encode(artist)}") { launchSingleTop = true }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable("album/{name}") { entry ->
                val name = entry.arguments?.getString("name") ?: return@composable
                AlbumScreen(
                    container,
                    name,
                    onArtistClick = { artist ->
                        navController.navigate("artist/${Uri.encode(artist)}") { launchSingleTop = true }
                    },
                    onAlbumClick = { album ->
                        navController.navigate("album/${Uri.encode(album)}") { launchSingleTop = true }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    container: AppContainer,
    np: com.cadence.music.playback.NowPlaying,
    layout: com.cadence.music.ui.theme.SkinLayout,
    onOpenNowPlaying: () -> Unit,
) {
    val queue by container.player.queueItems.collectAsStateWithLifecycle()
    val queueIdx by container.player.queueIndexFlow.collectAsStateWithLifecycle()
    val mediaId = queue.getOrNull(queueIdx)?.mediaId

    when (layout) {
        com.cadence.music.ui.theme.SkinLayout.SPOTIFY -> {
            androidx.compose.material3.Surface(
                color = Color(0xFF282828),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .clickable(
                        role = androidx.compose.ui.semantics.Role.Button,
                        onClickLabel = "Open now playing",
                        onClick = onOpenNowPlaying,
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrackArt(
                        container,
                        mediaId,
                        Modifier.size(38.dp),
                        RoundedCornerShape(4.dp),
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 10.dp, end = 6.dp)
                    ) {
                        Text(
                            np.title,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            np.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB3B3B3),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Crossfade(targetState = np.isPlaying, label = "miniPlaySpotify") { playing ->
                        IconButton(onClick = { container.player.togglePlayPause() }) {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                "Play/pause",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                }
            }
        }
        com.cadence.music.ui.theme.SkinLayout.APPLE -> {
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clickable(
                        role = androidx.compose.ui.semantics.Role.Button,
                        onClickLabel = "Open now playing",
                        onClick = onOpenNowPlaying,
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrackArt(
                        container,
                        mediaId,
                        Modifier.size(40.dp),
                        RoundedCornerShape(8.dp),
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 10.dp, end = 6.dp)
                    ) {
                        Text(
                            np.title,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            np.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Crossfade(targetState = np.isPlaying, label = "miniPlayApple") { playing ->
                        IconButton(onClick = { container.player.togglePlayPause() }) {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                "Play/pause",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(onClick = { container.player.next() }) {
                        Icon(
                            Icons.Filled.SkipNext,
                            "Next",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        else -> {
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clickable(
                        role = androidx.compose.ui.semantics.Role.Button,
                        onClickLabel = "Open now playing",
                        onClick = onOpenNowPlaying,
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrackArt(
                        container,
                        mediaId,
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
    }
}

private data class NavDest(
    val route: String,
    val label: String,
    val outlined: androidx.compose.ui.graphics.vector.ImageVector,
    val filled: androidx.compose.ui.graphics.vector.ImageVector,
)

/**
 * TURNTABLE skin navigation: an icons-only pill floating above the bottom
 * edge instead of the standard Material bar. Same routes, same state-restore
 * behavior — only the composition differs.
 */
@Composable
private fun FloatingPillNav(
    current: String?,
    tabs: List<NavDest>,
    onSelect: (String) -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            tabs.forEach { tab ->
                val selected = current == tab.route
                Box(
                    Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            else Color.Transparent,
                        )
                        .clickable(
                            role = androidx.compose.ui.semantics.Role.Button,
                            onClickLabel = tab.label,
                        ) { onSelect(tab.route) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (selected) tab.filled else tab.outlined,
                        tab.label,
                        Modifier.size(22.dp),
                        tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Launch update popup: when the startup check finds a newer release, offer the
 * changelog plus download/install. Once per tag — every close path (Later,
 * outside tap, Download) marks the tag seen.
 */
@Composable
private fun UpdatePopup(container: AppContainer) {
    val context = LocalContext.current
    val status by container.updateStatus.collectAsStateWithLifecycle()
    var dismissed by remember { mutableStateOf(false) }
    val avail = status as? UpdateStatus.Available ?: return
    if (dismissed || avail.tag == container.prefs.seenUpdateTag) return
    val install = remember(avail.tag) { container.installIntent(avail.tag) }

    fun close() {
        dismissed = true
        container.prefs.seenUpdateTag = avail.tag
    }

    AlertDialog(
        onDismissRequest = { close() },
        title = { Text("Cadence ${avail.tag} available") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    avail.changelog?.replace("**", "")?.takeIf { it.isNotBlank() }
                        ?: "No changelog in this release."
                )
            }
        },
        confirmButton = {
            if (install != null) {
                TextButton(onClick = {
                    runCatching { context.startActivity(install) }
                    close()
                }) { Text("Install") }
            } else {
                TextButton(onClick = {
                    container.downloadUpdate(avail.tag, avail.assetUrl)
                    close()
                }) { Text("Download") }
            }
        },
        dismissButton = {
            TextButton(onClick = { close() }) { Text("Later") }
        },
    )
}
