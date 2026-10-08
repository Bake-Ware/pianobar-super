package org.pianobarsuper.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.pianobarsuper.app.net.Playlist
import org.pianobarsuper.app.playback.Playback

@Composable
fun PlaylistsScreen(open: (String) -> Unit) {
    val repo = repo()
    val scope = rememberCoroutineScope()
    val playlists by repo.playlists.collectAsStateWithLifecycle()
    val snapshot by repo.snapshot.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { repo.refreshPlaylists(); repo.refreshLibrary() }
    val run = snapshot?.playlist
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
                Eyebrow("Your mixes")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Playlists", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = {
                        scope.launch {
                            var number = playlists.size + 1
                            while (playlists.any { it.name == "New playlist $number" }) number++
                            repo.savePlaylist(name = "New playlist $number", songs = emptyList())?.let { open(it.id) }
                        }
                    }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("New") }
                }
            }
        }
        if (snapshot?.djStation?.llmReady == true) item { AskTheDj(snapshot?.djStation?.settings?.dj_name ?: "", open) }
        if (playlists.isEmpty()) item {
            EmptyState("No playlists yet", "Make one, ask the DJ for one, or add songs from your Library with “Add to playlist”.")
        }
        items(playlists, key = { it.id }) { playlist ->
            val playing = run?.id == playlist.id
            Row(Modifier.fillMaxWidth().clickable { open(playlist.id) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (playlist.by == "dj") Icons.Rounded.AutoAwesome else Icons.AutoMirrored.Rounded.QueueMusic, null,
                    tint = if (playing) Palette.Orange else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text((if (playing) "♪ " else "") + playlist.name, style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull("${playlist.songs.size} ${if (playlist.songs.size == 1) "song" else "songs"}",
                        "by the DJ".takeIf { playlist.by == "dj" }, "playing".takeIf { playing }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { scope.launch { repo.playPlaylist(playlist.id) } }, enabled = playlist.songs.isNotEmpty()) {
                    Icon(Icons.Rounded.PlayArrow, "Play ${playlist.name}")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskTheDj(djName: String, open: (String) -> Unit) {
    val repo = repo()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var prompt by rememberSaveable { mutableStateOf("") }
    var count by rememberSaveable { mutableStateOf(20) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val who = djName.ifEmpty { "your DJ" }
    fun make() {
        if (prompt.isBlank() || working) return
        focus.clearFocus()
        working = true
        status = "${who.replaceFirstChar { it.uppercase() }} is digging through your crates…"
        scope.launch {
            val playlist = repo.djPlaylist(prompt, count)
            working = false
            status = if (playlist != null) "Made “${playlist.name}”." else "The DJ couldn’t make that one. Try describing it differently."
            if (playlist != null) { prompt = ""; open(playlist.id) }
        }
    }
    Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ask ${who} to make you one", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth().heightIn(min = 72.dp),
                placeholder = { Text("Rainy Sunday morning: mellow, acoustic, nothing too sad…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { make() }))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                var menu by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(menu, { menu = it }, Modifier.width(130.dp)) {
                    OutlinedTextField("$count songs", {}, readOnly = true, singleLine = true,
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) })
                    ExposedDropdownMenu(menu, { menu = false }) {
                        listOf(10, 20, 30, 45, 60).forEach { n -> DropdownMenuItem(text = { Text("$n songs") }, onClick = { count = n; menu = false }) }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (working) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Button(onClick = ::make, enabled = prompt.isNotBlank() && !working,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Orange, contentColor = Palette.PlayerDark)) { Text("Make it") }
            }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistDetailScreen(id: String, back: () -> Unit) {
    val repo = repo()
    val phone = phone()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val playlists by repo.playlists.collectAsStateWithLifecycle()
    val songs by repo.library.collectAsStateWithLifecycle()
    val snapshot by repo.snapshot.collectAsStateWithLifecycle()
    val tracks by phone.tracks.collectAsStateWithLifecycle()
    val playlist = playlists.firstOrNull { it.id == id }
    if (playlist == null) {
        LaunchedEffect(Unit) { repo.refreshPlaylists() }
        Column(Modifier.statusBarsPadding()) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            EmptyState("Playlist not found", "It may have been deleted.")
        }
        return
    }
    val byId = remember(songs) { songs.associateBy { it.id } }
    val local = remember(tracks) { tracks.associateBy { it.serverId } }
    val running = snapshot?.playlist?.id == playlist.id
    val currentId = snapshot?.state?.savedId ?: ""
    var name by remember(playlist.name) { mutableStateOf(playlist.name) }
    var description by remember(playlist.description) { mutableStateOf(playlist.description) }
    var confirmDelete by remember { mutableStateOf(false) }
    val available = playlist.songs.filter { it in byId }
    val total = available.sumOf { byId[it]?.duration ?: 0 }
    val downloaded = playlist.songs.mapNotNull { local[it] }
    fun save(change: Playlist.() -> Unit = {}) = scope.launch { repo.savePlaylist(id = playlist.id, name = name.ifBlank { playlist.name }, description = description) }
    fun saveSongs(list: List<String>) = scope.launch { repo.savePlaylist(id = playlist.id, songs = list) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.headlineSmall, singleLine = true,
                    label = { Text("Name") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); if (name.isNotBlank()) save() }))
                OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Description") },
                    placeholder = { Text("Add a description") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); save() }))
                if (name != playlist.name || description != playlist.description) Row {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { name = playlist.name; description = playlist.description }) { Text("Undo") }
                    Button(onClick = { focus.clearFocus(); save() }, enabled = name.isNotBlank()) { Text("Save") }
                }
                Text(listOfNotNull("${playlist.songs.size} ${if (playlist.songs.size == 1) "song" else "songs"}", if (total > 0) duration(total) else null,
                    "made by your DJ".takeIf { playlist.by == "dj" }, "${downloaded.size} on this phone".takeIf { downloaded.isNotEmpty() },
                    "${playlist.songs.size - available.size} no longer saved".takeIf { available.size < playlist.songs.size && songs.isNotEmpty() })
                    .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { scope.launch { repo.playPlaylist(playlist.id) } }, enabled = available.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Orange, contentColor = Palette.PlayerDark)) {
                        Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play")
                    }
                    OutlinedButton(onClick = { scope.launch { repo.playPlaylist(playlist.id, shuffle = true) } }, enabled = available.isNotEmpty()) {
                        Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle")
                    }
                    if (running) OutlinedButton(onClick = { scope.launch { repo.stopPlaylist() } }) {
                        Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop playlist")
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { phone.enqueue(playlist.songs.mapNotNull { byId[it] }) }, enabled = available.size > downloaded.size,
                        label = { Text(if (downloaded.size >= available.size && available.isNotEmpty()) "All on this phone" else "Download to phone") },
                        leadingIcon = { Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)) })
                    AssistChip(onClick = { Playback.playLocal(context, downloaded.map { it.id }) }, enabled = downloaded.isNotEmpty(),
                        label = { Text("Play on phone offline") }, leadingIcon = { Icon(Icons.Rounded.OfflinePin, null, Modifier.size(18.dp)) })
                    AssistChip(onClick = { confirmDelete = true }, label = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp)) })
                }
            }
            HorizontalDivider()
        }
        if (playlist.songs.isEmpty()) item { EmptyState("This playlist is empty", "Add songs from your Library with “Add to playlist”.") }
        itemsIndexed(playlist.songs, key = { index, songId -> "$index:$songId" }) { index, songId ->
            val song = byId[songId]
            val track = local[songId]
            SongRow(song?.label ?: track?.label ?: "Song no longer saved",
                listOf(song?.artist ?: track?.artist ?: "", song?.album ?: "").filter { it.isNotEmpty() }.joinToString(" · "),
                repo.artUrl(song?.cover), current = running && songId == currentId,
                trailing = song?.duration?.takeIf { it > 0 }?.let { time(it) } ?: "",
                onClick = if (song != null) ({ scope.launch { repo.playPlaylist(playlist.id, start = index) } }) else null) {
                IconButton(onClick = { saveSongs(playlist.songs.toMutableList().apply { add(index - 1, removeAt(index)) }) }, enabled = index > 0) {
                    Icon(Icons.Rounded.KeyboardArrowUp, "Move up")
                }
                IconButton(onClick = { saveSongs(playlist.songs.toMutableList().apply { add(index + 1, removeAt(index)) }) }, enabled = index < playlist.songs.size - 1) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Move down")
                }
                IconButton(onClick = { saveSongs(playlist.songs.filterIndexed { i, _ -> i != index }) }) { Icon(Icons.Rounded.Close, "Remove") }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete “${playlist.name}”?") },
        text = { Text("The playlist is removed from your server. Songs stay in your library and on this phone.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch { if (repo.deletePlaylist(playlist.id)) back() } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

/** Add songs to an existing playlist or a new one. */
@Composable
fun AddToPlaylistDialog(ids: List<String>, label: String, dismiss: () -> Unit) {
    val repo = repo()
    val scope = rememberCoroutineScope()
    val playlists by repo.playlists.collectAsStateWithLifecycle()
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { repo.refreshPlaylists() }
    fun done(playlist: Playlist?) { if (playlist != null) repo.messages.tryEmit("Added to “${playlist.name}”."); dismiss() }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Column { Text("Add to playlist"); Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                playlists.forEach { playlist ->
                    val has = ids.all { it in playlist.songs }
                    Row(Modifier.fillMaxWidth().clickable(enabled = !has) { scope.launch { done(repo.addToPlaylist(playlist.id, ids)) } }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name, color = if (has) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            Text(if (has) "Already added" else "${playlist.songs.size} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it }, Modifier.weight(1f), placeholder = { Text("New playlist name") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (newName.isNotBlank()) scope.launch { done(repo.savePlaylist(name = newName, songs = ids)) } }))
                    TextButton(onClick = { scope.launch { done(repo.savePlaylist(name = newName, songs = ids)) } }, enabled = newName.isNotBlank()) { Text("Create") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}
