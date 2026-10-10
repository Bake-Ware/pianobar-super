package org.pianobarsuper.app.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.pianobarsuper.app.data.Connection
import org.pianobarsuper.app.playback.Playback
import org.pianobarsuper.app.playback.StreamStatus

enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    NowPlaying("player", "Now playing", Icons.Rounded.Album),
    Stations("stations", "Stations", Icons.Rounded.Radio),
    Playlists("playlists", "Playlists", Icons.AutoMirrored.Rounded.QueueMusic),
    Library("library", "Library", Icons.Rounded.LibraryMusic),
    More("more", "More", Icons.Rounded.MoreHoriz),
}

@Composable
fun AppRoot(onThemeChange: (String) -> Unit) {
    val repo = repo()
    val connection by repo.connection.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { repo.messages.collect { snackbar.showSnackbar(it) } }
    if (connection is Connection.NoServer) {
        SetupScreen()
        return
    }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Tab.NowPlaying.route
    val tab = Tab.entries.firstOrNull { route == it.route || route.startsWith(it.route + "/") } ?: Tab.More
    fun go(target: Tab) {
        // Tapping the current tab again returns to its top level.
        if (target == tab) { nav.popBackStack(target.route, inclusive = false); return }
        nav.navigate(target.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) NavigationRail(Modifier.padding(top = 8.dp)) {
                Tab.entries.forEach { t ->
                    NavigationRailItem(selected = t == tab, onClick = { go(t) }, icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                snackbarHost = { SnackbarHost(snackbar) },
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    Column {
                        if (tab != Tab.NowPlaying) MiniPlayer { go(Tab.NowPlaying) }
                        if (!wide) NavigationBar {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(selected = t == tab, onClick = { go(t) }, icon = { Icon(t.icon, null) },
                                    label = { Text(t.label, maxLines = 1) })
                            }
                        }
                    }
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    ConnectionBanner(connection)
                    AppNavHost(nav, onThemeChange)
                }
            }
        }
    }
    PromptDialog()
    UpdatePermissionGuide()
}

@Composable
private fun AppNavHost(nav: NavHostController, onThemeChange: (String) -> Unit) {
    NavHost(nav, startDestination = Tab.NowPlaying.route) {
        composable(Tab.NowPlaying.route) { NowPlayingScreen(openStations = { nav.navigate(Tab.Stations.route) }, openPlaylist = { nav.navigate("playlists/$it") }) }
        composable(Tab.Stations.route) { StationsScreen() }
        composable(Tab.Playlists.route) { PlaylistsScreen(open = { nav.navigate("playlists/$it") }) }
        composable("playlists/{id}") { PlaylistDetailScreen(it.arguments?.getString("id") ?: "", back = { nav.popBackStack() }) }
        composable(Tab.Library.route) { LibraryScreen() }
        composable(Tab.More.route) { MoreScreen(open = { nav.navigate(it) }, onThemeChange = onThemeChange) }
        composable("more/settings") { SettingsScreen(back = { nav.popBackStack() }) }
        composable("more/downloads") { DownloadsScreen(back = { nav.popBackStack() }) }
        composable("more/devices") { DevicesScreen(back = { nav.popBackStack() }) }
        composable("more/commands") { CommandsScreen(back = { nav.popBackStack() }) }
        composable("more/log") { LogScreen(back = { nav.popBackStack() }) }
    }
}

/** Sign in, offline and reconnecting notices across every screen. */
@Composable
private fun ConnectionBanner(connection: Connection) {
    val context = LocalContext.current
    val repo = repo()
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) repo.reconnect()
    }
    // The first time a sign-in is needed in this session, open it straight away.
    var prompted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(connection) {
        if (connection == Connection.SignInRequired && !prompted) {
            prompted = true
            signIn.launch(Intent(context, SignInActivity::class.java))
        }
    }
    when (connection) {
        Connection.SignInRequired -> Banner(Icons.AutoMirrored.Rounded.Login, "Sign in to keep listening and controlling your radio.", "Sign in") {
            signIn.launch(Intent(context, SignInActivity::class.java))
        }
        is Connection.Unreachable -> Banner(Icons.Rounded.CloudOff, "Can’t reach your server. Downloaded songs still play from More → Downloads.", "Retry") {
            repo.reconnect()
        }
        else -> Unit
    }
}

@Composable
private fun Banner(icon: ImageVector, text: String, action: String, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/** Current song, server play/pause and next, and whether this phone is listening. */
@Composable
private fun MiniPlayer(open: () -> Unit) {
    val repo = repo()
    val context = LocalContext.current
    val snapshot by repo.snapshot.collectAsStateWithLifecycle()
    val phone by Playback.state.collectAsStateWithLifecycle()
    val tracks by phone().tracks.collectAsStateWithLifecycle()
    val state = snapshot?.state
    if (phone.local) {
        val track = tracks.firstOrNull { it.id == phone.localTrack } ?: return
        MiniBar(track.label, track.artist, null, phone().cover(track.id), phone.localPlaying,
            if (phone.durationMs > 0) phone.positionMs.toFloat() / phone.durationMs else 0f, false, open,
            onToggle = { Playback.local()?.let { if (it.isPlaying) it.pause() else it.play() } },
            onNext = { Playback.local()?.seekToNextMediaItem() })
        return
    }
    if (state == null || state.title.isEmpty()) return
    MiniBar(state.title, state.artist, repo.artUrl(state.cachedCover.ifEmpty { state.cover }), null, !state.paused,
        if (state.duration > 0) state.elapsed.toFloat() / state.duration else 0f,
        phone.listening && phone.stream == StreamStatus.Playing, open,
        onToggle = { repo.action("act_songpausetoggle") }, onNext = { repo.action("act_songnext") })
}

@Composable
private fun MiniBar(title: String, artist: String, art: String?, artModel: Any?, playing: Boolean, progress: Float, listening: Boolean,
                    open: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 2.dp) {
        Column {
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().size(height = 2.dp, width = 0.dp),
                color = MaterialTheme.colorScheme.secondary, trackColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(art, 40.dp, model = artModel, corner = 4.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AnimatedVisibility(listening) {
                            Icon(Icons.Rounded.Headphones, "Listening on this phone", Modifier.size(14.dp).padding(end = 4.dp), tint = MaterialTheme.colorScheme.secondary)
                        }
                        Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onToggle) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play") }
                IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, "Next song") }
            }
        }
    }
}
