package org.pianobarsuper.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.pianobarsuper.app.BuildConfig
import org.pianobarsuper.app.data.DownloadState
import org.pianobarsuper.app.net.ClientPage
import org.pianobarsuper.app.net.title
import org.pianobarsuper.app.playback.Playback

@Composable
fun MoreScreen(open: (String) -> Unit, onThemeChange: (String) -> Unit) {
    val repo = repo()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val phone by Playback.state.collectAsStateWithLifecycle()
    var theme by remember { mutableStateOf(repo.store.theme) }
    var deviceName by remember { mutableStateOf(repo.store.deviceName) }
    var autoListen by remember { mutableStateOf(repo.store.autoListen) }
    var confirmSignOut by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.statusBarsPadding().padding(16.dp)) {
            Wordmark()
            Text(repo.store.origin ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MoreItem(Icons.Rounded.DownloadForOffline, "Downloads", "Songs kept on this phone, playable offline") { open("more/downloads") }
        MoreItem(Icons.Rounded.Settings, "Server settings", "Listening, Pandora, DJ, connections and web access") { open("more/settings") }
        MoreItem(Icons.Rounded.Devices, "Listening devices", "Browsers and phones playing your radio") { open("more/devices") }
        MoreItem(Icons.Rounded.Keyboard, "Player commands", "Every pianobar command, including station tools") { open("more/commands") }
        MoreItem(Icons.Rounded.Terminal, "Player log", "What the player has been saying") { open("more/log") }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle("This app")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Theme", style = MaterialTheme.typography.bodyMedium)
            val themes = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                themes.forEachIndexed { index, (value, label) ->
                    SegmentedButton(theme == value, { theme = value; onThemeChange(value) }, SegmentedButtonDefaults.itemShape(index, themes.size)) { Text(label) }
                }
            }
            OutlinedTextField(deviceName, { deviceName = it }, Modifier.fillMaxWidth(), label = { Text("This phone’s name") }, singleLine = true,
                supportingText = { Text("How this phone appears in Listening devices. Applies the next time it starts listening.") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); repo.store.deviceName = deviceName }))
            if (deviceName != repo.store.deviceName && deviceName.isNotBlank()) Button(onClick = { focus.clearFocus(); repo.store.deviceName = deviceName }) { Text("Save name") }
            Row(Modifier.fillMaxWidth().clickable { autoListen = !autoListen; repo.store.autoListen = autoListen }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Listen when the app opens", style = MaterialTheme.typography.bodyLarge)
                    Text("Starts playing the radio on this phone as soon as you open the app.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(autoListen, { autoListen = it; repo.store.autoListen = it })
            }
        }
        Spacer(Modifier.padding(top = 12.dp))
        UpdatesSection()
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ListItem(
            headlineContent = { Text("Change server or sign out") },
            supportingContent = { Text("Forget the web password and sign-in cookies on this phone.") },
            leadingContent = { Icon(Icons.AutoMirrored.Rounded.Logout, null) },
            modifier = Modifier.clickable { confirmSignOut = true },
        )
        Text("pianobar for Android · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
    }
    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text("Sign out?") },
        text = { Text("This phone stops listening and forgets how to sign in. Downloads stay on the phone.") },
        confirmButton = {
            TextButton(onClick = {
                confirmSignOut = false
                if (phone.listening) Playback.stopListening(context)
                repo.store.signOut()
                repo.store.server = ""
                repo.reconnect()
            }) { Text("Sign out") }
        },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
    )
}

@Composable
private fun MoreItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) }, leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = onClick))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubPage(title: String, back: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = { actions() })
        content()
    }
}

@Composable
fun DownloadsScreen(back: () -> Unit) {
    val library = phone()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = repo()
    val tracks by library.tracks.collectAsStateWithLifecycle()
    val downloads by library.downloads.collectAsStateWithLifecycle()
    val playback by Playback.state.collectAsStateWithLifecycle()
    val librarySongs by repo.library.collectAsStateWithLifecycle()
    var remove by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        scope.launch {
            for (uri in uris) try { library.importFile(uri) } catch (e: Exception) { repo.messages.tryEmit(e.message ?: "Could not import that file.") }
        }
    }
    SubPage("Downloads", back, actions = {
        IconButton(onClick = { picker.launch(arrayOf("audio/*")) }) { Icon(Icons.Rounded.FileOpen, "Import audio files") }
    }) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text("Songs here play on this phone with or without a connection. Download from Library or a playlist, or import audio files.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                if (tracks.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Playback.playLocal(context, tracks.map { it.id }) }) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play all") }
                    OutlinedButton(onClick = { Playback.playLocal(context, tracks.shuffled().map { it.id }) }) { Text("Shuffle") }
                }
            }
            val pending = downloads.entries.toList()
            if (pending.isNotEmpty()) {
                item { SectionTitle("In progress") }
                items(pending, key = { "d:" + it.key }) { (id, state) ->
                    val song = librarySongs.firstOrNull { it.id == id }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        when (state) {
                            is DownloadState.Running -> CircularProgressIndicator(progress = { state.fraction }, Modifier.size(24.dp), strokeWidth = 2.dp)
                            DownloadState.Queued -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            is DownloadState.Failed -> Icon(Icons.Rounded.Close, null, tint = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song?.label ?: "Saved song")
                            Text(if (state is DownloadState.Failed) state.message else song?.artist ?: "", style = MaterialTheme.typography.bodySmall,
                                color = if (state is DownloadState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (pending.any { it.value is DownloadState.Failed }) item {
                    TextButton(onClick = { library.dismissFailed() }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Clear failed") }
                }
            }
            item { SectionTitle("On this phone · ${tracks.size}") }
            if (tracks.isEmpty()) item { EmptyState("Nothing downloaded yet", "Open a song’s menu in Library and choose “Download to this phone”.") }
            items(tracks, key = { it.id }) { track ->
                val index = tracks.indexOf(track)
                SongRow(track.label, listOf(track.artist, track.album).filter { it.isNotEmpty() }.joinToString(" · "), null,
                    artModel = library.cover(track.id), current = playback.local && playback.localTrack == track.id,
                    trailing = if (track.duration > 0) time(track.duration) else "",
                    onClick = { Playback.playLocal(context, tracks.map { it.id }, index) }) {
                    IconButton(onClick = { remove = track.id }) { Icon(Icons.Rounded.Delete, "Remove ${track.label} from this phone") }
                }
            }
        }
    }
    remove?.let { id ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove from this phone?") },
            text = { Text("It stays in your server library and can be downloaded again.") },
            confirmButton = { TextButton(onClick = { library.remove(id); remove = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } })
    }
}

@Composable
fun DevicesScreen(back: () -> Unit) {
    val repo = repo()
    val scope = rememberCoroutineScope()
    val phone by Playback.state.collectAsStateWithLifecycle()
    var clients by remember { mutableStateOf<List<ClientPage>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) { while (true) { clients = repo.clients()?.clients; delay(5000) } }
    SubPage("Listening devices", back, actions = { IconButton(onClick = { refresh++ }) { Icon(Icons.Rounded.Refresh, "Refresh") } }) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text("Browsers and phones that joined as listeners. Turn one off to mute it, or set its own volume. The server plays to them when its output is Devices or Both.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scope.launch { repo.routeClients(buildJsonObject { put("mode", "all") }); refresh++ } }) { Text("All on") }
                    OutlinedButton(onClick = { scope.launch { repo.routeClients(buildJsonObject { put("mode", "none") }); refresh++ } }) { Text("All off") }
                }
            }
            val list = clients
            if (list == null) item { Text("Loading…", Modifier.padding(16.dp)) }
            else if (list.isEmpty()) item { EmptyState("No listening devices", "Open the web player or tap the headphones on Now playing.") }
            else items(list, key = { it.id }) { client ->
                var volume by remember(client.volume) { mutableStateOf(client.volume.toFloat()) }
                val mine = client.name == repo.store.deviceName && phone.listening
                Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(client.name + if (mine) " (this phone)" else "", style = MaterialTheme.typography.titleSmall)
                                Text(listOf(client.status.replaceFirstChar { it.uppercase() },
                                    if (client.underruns > 0) "${client.underruns} hiccups" else "", "seen ${client.lastSeenSeconds.toInt()} s ago")
                                    .filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(client.enabled, { on -> scope.launch { repo.updateClient(buildJsonObject { put("id", client.id); put("enabled", on) }); refresh++ } })
                        }
                        Slider(volume, { volume = it }, onValueChangeFinished = {
                            scope.launch { repo.updateClient(buildJsonObject { put("id", client.id); put("volume", volume.toDouble()) }) }
                        })
                    }
                }
            }
        }
    }
}

@Composable
fun CommandsScreen(back: () -> Unit) {
    val repo = repo()
    val snapshot by repo.snapshot.collectAsStateWithLifecycle()
    val actions = snapshot?.state?.actions ?: emptyList()
    SubPage("Player commands", back) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text("Every pianobar command. Some ask a short question next, such as a station name or a choice.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            items(actions, key = { it.id }) { action ->
                ListItem(
                    headlineContent = { Text(action.title(), color = if (action.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = { Text(if (action.key == " ") "Space" else action.key, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    modifier = Modifier.clickable(enabled = action.enabled && snapshot?.pending == false) { repo.action(action.id) },
                )
            }
        }
    }
}

@Composable
fun LogScreen(back: () -> Unit) {
    val repo = repo()
    val snapshot by repo.snapshot.collectAsStateWithLifecycle()
    val lines = remember(snapshot?.output) { (snapshot?.output ?: "").trimEnd().lines().takeLast(400) }
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }
    SubPage("Player log", back) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = list) {
            items(lines.size) { index ->
                Text(lines[index], fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}
