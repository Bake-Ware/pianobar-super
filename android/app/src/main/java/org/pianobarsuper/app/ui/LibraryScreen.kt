package org.pianobarsuper.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.pianobarsuper.app.data.DownloadState
import org.pianobarsuper.app.net.Song

private data class SongGroup(val key: String, val name: String, val detail: String, val songs: List<Song>)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val repo = repo()
    val songs by repo.library.collectAsStateWithLifecycle()
    val snapshot by repo.snapshot.collectAsStateWithLifecycle()
    val currentId = snapshot?.state?.savedId ?: ""
    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf<Pair<List<String>, String>?>(null) }
    LaunchedEffect(Unit) { while (true) { repo.refreshLibrary(); delay(10_000) } }
    val filtered = remember(songs, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) songs else songs.filter { s -> listOf(s.title, s.artist, s.album, *s.genres.toTypedArray()).any { it.lowercase().contains(q) } }
    }
    val groups = remember(filtered, tab) {
        when (tab) {
            1 -> filtered.groupBy { it.artist }.map { (artist, list) -> SongGroup(artist, artist.ifEmpty { "Unknown artist" }, "", list.sortedBy { it.title.lowercase() }) }
            2 -> filtered.groupBy { it.album to it.artist }.map { (key, list) -> SongGroup(key.first + "\u0000" + key.second, key.first.ifEmpty { "Unknown album" }, key.second.ifEmpty { "Unknown artist" }, list) }
            3 -> buildMap<String, MutableList<Song>> {
                for (song in filtered) {
                    if (song.genres.isEmpty()) getOrPut("\u0000none") { mutableListOf() }.add(song)
                    else song.genres.forEach { getOrPut(it.lowercase()) { mutableListOf() }.add(song) }
                }
            }.map { (key, list) ->
                SongGroup(key, if (key == "\u0000none") "No genre yet" else list.first().genres.first { it.lowercase() == key },
                    if (key == "\u0000none") "Genres come from MusicBrainz after a song plays" else "", list.sortedBy { it.title.lowercase() })
            }
            else -> emptyList()
        }.sortedWith(compareBy({ it.key.startsWith("\u0000") }, { it.name.lowercase() }))
    }
    var open by rememberSaveable { mutableStateOf(setOf<String>()) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            Eyebrow("Available offline")
            Text("Saved songs · ${songs.size}", style = MaterialTheme.typography.headlineLarge)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = { Text("Song, artist, album or genre…") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true,
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } })
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("Songs", "Artists", "Albums", "Genres").forEachIndexed { index, name ->
                Tab(tab == index, { tab = index }, text = { Text(name) })
            }
        }
        if (songs.isEmpty()) {
            EmptyState("No saved songs yet", "Songs are saved on the server as they play when “Save songs automatically” is on in Settings.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (tab == 0) {
                items(filtered, key = { it.id }) { song ->
                    LibrarySong(song, current = song.id == currentId, onAdd = { adding = listOf(song.id) to song.label })
                }
            } else {
                items(groups, key = { it.key }) { group ->
                    val expanded = group.key in open
                    GroupRow(group, expanded, onToggle = { open = if (expanded) open - group.key else open + group.key },
                        onAdd = { adding = group.songs.map { it.id } to group.name })
                    if (expanded) group.songs.forEach { song ->
                        LibrarySong(song, current = song.id == currentId, indent = true, onAdd = { adding = listOf(song.id) to song.label })
                    }
                }
            }
            if (filtered.isEmpty()) item { EmptyState("Nothing matches", "Try a different search.") }
        }
    }
    adding?.let { (ids, label) -> AddToPlaylistDialog(ids, label) { adding = null } }
}

@Composable
private fun GroupRow(group: SongGroup, expanded: Boolean, onToggle: () -> Unit, onAdd: () -> Unit) {
    val repo = repo()
    val phone = phone()
    var menu by remember { mutableStateOf(false) }
    val cover = group.songs.firstOrNull { it.cover.isNotEmpty() }?.cover
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(repo.artUrl(cover), 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(group.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(group.detail, "${group.songs.size} ${if (group.songs.size == 1) "song" else "songs"}").filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { repo.playSongs(group.songs.map { it.id }) }) { Icon(Icons.Rounded.PlayArrow, "Play ${group.name}") }
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More for ${group.name}") }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Add to playlist") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) }, onClick = { menu = false; onAdd() })
            DropdownMenuItem(text = { Text("Download to this phone") }, leadingIcon = { Icon(Icons.Rounded.Download, null) },
                onClick = { menu = false; phone.enqueue(group.songs) })
        }
    }
}

@Composable
fun LibrarySong(song: Song, current: Boolean, indent: Boolean = false, onAdd: () -> Unit) {
    val repo = repo()
    val phone = phone()
    val tracks by phone.tracks.collectAsStateWithLifecycle()
    val downloads by phone.downloads.collectAsStateWithLifecycle()
    val downloaded = tracks.any { it.serverId == song.id }
    val download = downloads[song.id]
    var menu by remember { mutableStateOf(false) }
    SongRow(song.label, listOf(song.artist, song.album).filter { it.isNotEmpty() }.joinToString(" · "), repo.artUrl(song.cover),
        modifier = if (indent) Modifier.padding(start = 24.dp) else Modifier, current = current,
        trailing = if (song.duration > 0) time(song.duration) else "", onClick = { repo.playSaved(song.id) }) {
        when {
            download is DownloadState.Running -> CircularProgressIndicator(progress = { download.fraction }, Modifier.size(20.dp), strokeWidth = 2.dp)
            download is DownloadState.Queued -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            downloaded -> Icon(Icons.Rounded.DownloadDone, "On this phone", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
        }
        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More for ${song.label}") }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Play") }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) }, onClick = { menu = false; repo.playSaved(song.id) })
            DropdownMenuItem(text = { Text("Add to playlist") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) }, onClick = { menu = false; onAdd() })
            if (!downloaded) DropdownMenuItem(text = { Text(if (download is DownloadState.Failed) "Retry download" else "Download to this phone") },
                leadingIcon = { Icon(Icons.Rounded.Download, null) }, onClick = { menu = false; phone.dismissFailed(); phone.enqueue(listOf(song)) })
        }
    }
}
