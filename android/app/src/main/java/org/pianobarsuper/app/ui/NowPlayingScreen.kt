package org.pianobarsuper.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HeadsetOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.pianobarsuper.app.R
import org.pianobarsuper.app.net.Snapshot
import org.pianobarsuper.app.playback.Playback
import org.pianobarsuper.app.playback.PhonePlayback
import org.pianobarsuper.app.playback.StreamStatus
import java.net.URLEncoder

@Composable
fun NowPlayingScreen(openStations: () -> Unit, openPlaylist: (String) -> Unit) {
    val phone by Playback.state.collectAsStateWithLifecycle()
    if (phone.local) { LocalNowPlaying(phone); return }
    val repo = repo()
    val snapshot by repo.snapshot.collectAsStateWithLifecycle()
    val current = snapshot
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Connecting to your radio…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight && maxWidth >= 600.dp
        if (wide) {
            Row(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(.9f).fillMaxSize(), verticalArrangement = Arrangement.Center) { Cover(current) }
                Column(Modifier.weight(1.1f).verticalScroll(rememberScrollState())) { Details(current, phone, openStations, openPlaylist) }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 560.dp)) {
                    Cover(current, Modifier.padding(horizontal = 12.dp))
                    Spacer(Modifier.height(20.dp))
                    Details(current, phone, openStations, openPlaylist)
                }
            }
        }
    }
}

@Composable
private fun Cover(snapshot: Snapshot, modifier: Modifier = Modifier) {
    val repo = repo()
    val state = snapshot.state
    val art = repo.artUrl(state.cachedCover.ifEmpty { state.metadata?.cover?.ifEmpty { null } ?: state.cover })
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, 420.dp)
        Artwork(art, side, corner = 10.dp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Details(snapshot: Snapshot, phone: PhonePlayback, openStations: () -> Unit, openPlaylist: (String) -> Unit) {
    val repo = repo()
    val busy by repo.busy.collectAsStateWithLifecycle()
    val state = snapshot.state
    val run = snapshot.playlist
    val ready = !busy && !snapshot.pending && !snapshot.prompt.active
    // Source line, as on the web: playlist, offline library or station.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(6.dp), shape = CircleShape, color = Palette.Orange) {}
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                run != null -> "PLAYLIST · ${run.name.uppercase()} · ${run.position + 1} OF ${run.total}"
                state.offline -> "FROM YOUR OFFLINE LIBRARY"
                else -> state.station.uppercase().ifEmpty { "YOUR PERSONAL RADIO" }
            },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).clickable { if (run != null) openPlaylist(run.id) else if (!state.offline) openStations() },
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        SongLinks(state.title, state.artist, state.metadata?.recordingId ?: "")
    }
    Spacer(Modifier.height(8.dp))
    Text(state.title.ifEmpty { "Something good is next." }, style = MaterialTheme.typography.headlineMedium)
    Text(state.artist.ifEmpty { "Connect to your station and make yourself at home." }, style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.album.isNotEmpty()) Text(state.album, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val meta = state.metadata
    val genres = meta?.genres?.joinToString(", ") ?: ""
    val metaLine = listOf(if (genres.isNotEmpty() && meta?.genreScope == "artist") "Artist genres: $genres" else genres, meta?.releaseDate ?: "")
        .filter { it.isNotEmpty() }.joinToString(" · ")
    if (metaLine.isNotEmpty()) Text(metaLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(16.dp))
    Progress(snapshot)
    Spacer(Modifier.height(8.dp))
    // Transport: love, play/pause on the server, next, and listen on this phone.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { repo.action("act_songlove") }, enabled = ready && state.can("act_songlove")) {
            Icon(if (state.loved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Love song",
                tint = if (state.loved) Palette.Orange else MaterialTheme.colorScheme.onSurface)
        }
        FilledIconButton(onClick = { repo.action("act_songpausetoggle") }, enabled = ready && state.can("act_songpausetoggle"),
            modifier = Modifier.size(72.dp), shape = RoundedCornerShape(18.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Palette.Orange, contentColor = Palette.PlayerDark)) {
            Icon(if (state.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (state.paused) "Resume playback" else "Pause playback", Modifier.size(36.dp))
        }
        IconButton(onClick = { repo.action("act_songnext") }, enabled = ready && state.can("act_songnext")) {
            Icon(Icons.Rounded.SkipNext, "Next song", Modifier.size(32.dp))
        }
        ListenButton(phone)
    }
    ListenStatus(phone)
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        TrackAction("Ban", Icons.Rounded.Block, ready && state.can("act_songban")) { repo.action("act_songban") }
        TrackAction("Rest", Icons.Rounded.Bedtime, ready && state.can("act_songtired")) { repo.action("act_songtired") }
        TrackAction("Bookmark", Icons.Rounded.BookmarkAdd, ready && state.can("act_bookmark")) { repo.action("act_bookmark") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { repo.action("act_voldown") }, enabled = ready && state.can("act_voldown")) { Icon(Icons.AutoMirrored.Rounded.VolumeDown, "Decrease volume") }
            Text("${state.volume} dB", style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = { repo.action("act_volup") }, enabled = ready && state.can("act_volup")) { Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Increase volume") }
        }
    }
    if (state.nextTitle.isNotEmpty()) {
        Text("Up next: ${state.nextTitle} · ${state.nextArtist}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 4.dp))
    }
    Spacer(Modifier.height(12.dp))
    DjBooth(snapshot)
    Spacer(Modifier.height(12.dp))
    OutputCard(snapshot)
    Spacer(Modifier.height(24.dp))
    if (snapshot.playerStopped || snapshot.exited) Text("The player has stopped. Check your Pandora account or offline library in Settings, then restart the player.",
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TrackAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    AssistChip(onClick = onClick, enabled = enabled, label = { Text(label) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) })
}

/** Elapsed time ticks locally between server updates. */
@Composable
private fun Progress(snapshot: Snapshot) {
    val state = snapshot.state
    var received by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.elapsed, state.songKey, state.paused) { received = System.currentTimeMillis(); now = received }
    LaunchedEffect(state.paused) { while (!state.paused) { delay(500); now = System.currentTimeMillis() } }
    val elapsed = (state.elapsed + if (state.paused) 0 else ((now - received) / 1000).toInt()).coerceAtMost(maxOf(state.duration, state.elapsed))
    LinearProgressIndicator(progress = { if (state.duration > 0) elapsed.toFloat() / state.duration else 0f },
        Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = Palette.Orange,
        trackColor = MaterialTheme.colorScheme.surfaceVariant)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(time(elapsed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(time(state.duration), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ListenButton(phone: PhonePlayback) {
    val context = LocalContext.current
    val on = phone.listening
    val tint by animateColorAsState(if (on) Palette.Orange else MaterialTheme.colorScheme.onSurface, label = "listen")
    // The media notification needs permission on Android 13+; ask the first time you listen.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Playback.listen(context) }
    FilledIconToggleButton(checked = on, onCheckedChange = {
        if (!it) Playback.stopListening(context)
        else if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else Playback.listen(context)
    },
        colors = IconButtonDefaults.filledIconToggleButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
            checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Icon(if (on) Icons.Rounded.Headphones else Icons.Rounded.HeadsetOff, if (on) "Stop listening on this phone" else "Listen on this phone", tint = tint)
    }
}

@Composable
private fun ListenStatus(phone: PhonePlayback) {
    val text = when {
        !phone.listening -> phone.message
        phone.stream == StreamStatus.Connecting -> "Joining as a listening device…"
        phone.stream == StreamStatus.Waiting -> "Listening on this phone · waiting for audio"
        phone.stream == StreamStatus.Playing -> "Listening on this phone"
        phone.stream == StreamStatus.Reconnecting -> phone.message.ifEmpty { "Reconnecting…" }
        else -> phone.message
    }
    AnimatedVisibility(text.isNotEmpty()) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** YouTube search and MusicBrainz (the recording, or a search when it was not matched). */
@Composable
private fun SongLinks(title: String, artist: String, recordingId: String) {
    if (title.isEmpty() || artist.isEmpty()) return
    val context = LocalContext.current
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    IconButton(onClick = { open("https://www.youtube.com/results?search_query=" + enc("$artist $title official music video")) }, modifier = Modifier.size(36.dp)) {
        Icon(painterResource(R.drawable.ic_youtube), "Find on YouTube", tint = Color.Unspecified, modifier = Modifier.size(width = 24.dp, height = 18.dp))
    }
    IconButton(onClick = {
        open(if (recordingId.matches(Regex("[0-9a-f-]{36}"))) "https://musicbrainz.org/recording/$recordingId"
        else "https://musicbrainz.org/search?type=recording&method=indexed&query=" + enc("recording:\"$title\" AND artist:\"$artist\""))
    }, modifier = Modifier.size(36.dp)) {
        Icon(painterResource(R.drawable.ic_musicbrainz), "MusicBrainz", tint = Color.Unspecified, modifier = Modifier.size(20.dp))
    }
}

private val voiceStates = mapOf("writing" to "Writing an intro…", "preparing" to "Warming up the mic…", "on_air" to "On air")
private val stationStates = mapOf("choosing" to "Picking the next set…", "preparing_intro" to "Writing the set intro…", "waiting_listener" to "Waiting for music to play")

@Composable
private fun DjBooth(snapshot: Snapshot) {
    val repo = repo()
    val station = snapshot.djStation ?: return
    val voice = snapshot.djVoice
    val state = snapshot.state
    if (!station.llmReady && !station.voiceReady) return
    val active = voice?.status in setOf("writing", "preparing", "on_air")
    val busyStation = if (station.enabled) stationStates[station.status] else null
    val label = voiceStates[voice?.status] ?: busyStation ?: when {
        station.enabled -> "Running the station"; station.hop -> "Hopping stations"; station.talk -> "Standing by"; else -> "Off"
    }
    val onAir = voice?.status == "on_air"
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = if (onAir) BorderStroke(1.dp, Palette.Orange) else null) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(8.dp), shape = CircleShape, color = when {
                    onAir -> Palette.Orange; active || busyStation != null -> MaterialTheme.colorScheme.tertiary
                    station.talk || station.enabled || station.hop -> MaterialTheme.colorScheme.tertiary.copy(alpha = .6f)
                    else -> MaterialTheme.colorScheme.outline
                }) {}
                Spacer(Modifier.width(10.dp))
                Text(station.settings.dj_name.ifEmpty { "Your DJ" }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            if (station.voiceReady) DjSwitch("Talks", "Introduces songs as they start", station.talk) {
                repo.djControl(buildJsonObject { put("talk", it) })
            }
            if (station.llmReady) DjSwitch("Picks music", if (state.offline || station.enabled) "Builds sets from your saved songs"
                else "Builds sets from your saved songs (switches to offline playback)", station.enabled) {
                repo.djControl(buildJsonObject { put("enabled", it) })
            }
            DjSwitch("Hops stations", if (state.offline && !station.hop) "Reconnect to Pandora to hop stations"
                else "Moves to a random Pandora station every ${station.hopSongs} songs", station.hop, enabled = station.hop || !state.offline) {
                repo.djControl(buildJsonObject { put("hop", it) })
            }
            // The last line stays up after it airs, so you can read what was said.
            val line = if (voice != null && voice.text.isNotEmpty() && (active || voice.status == "done") && voice.songKey == state.songKey) voice.text else ""
            if (line.isNotEmpty() || voice?.status == "writing") {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (line.isNotEmpty()) "“${spokenText(line)}”" else "Writing…", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f), fontWeight = if (onAir) FontWeight.Medium else FontWeight.Normal)
                        if (active) IconButton(onClick = { repo.djControl(buildJsonObject { put("stopVoice", true) }) }) { Icon(Icons.Rounded.Stop, "Stop the DJ") }
                    }
                }
            }
            val notices = listOfNotNull(voice?.error?.takeIf { voice.status == "error" && it.isNotEmpty() },
                station.error.takeIf { station.enabled && station.status == "error" && it.isNotEmpty() })
            notices.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            // Lineup, hop status and theme.
            val set = station.currentSet
            val next = station.next
            val hopNext = if (station.hop) station.hopNext?.toString()?.trim('"')?.let { id -> state.stations.firstOrNull { it.id == id }?.name } else null
            val title = when {
                set != null -> "This set · ${set.position} of ${set.songs.size}" + if (set.duration > 0) " · ${time(set.duration)}" else ""
                next != null -> "Up next"
                hopNext != null -> "Next station: $hopNext"
                station.hop && !state.offline -> "Station hop · ${station.hopCount} of ${station.hopSongs}"
                else -> "Lineup & theme"
            }
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(top = 4.dp)) {
                Text(title, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (station.hop && !state.offline) Text(
                        if (hopNext != null) "Switching to $hopNext after this song."
                        else "Song ${maxOf(1, station.hopCount)} of ${station.hopSongs} on ${state.station.ifEmpty { "this station" }}, then a random station.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    set?.songs?.forEachIndexed { index, song ->
                        val now = index + 1 == set.position
                        Text("${song.artist.ifEmpty { "Unknown artist" }} — ${song.title.ifEmpty { "Untitled" }}",
                            style = MaterialTheme.typography.bodyMedium, color = when {
                                now -> Palette.Orange; index + 1 < set.position -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurface
                            }, fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal)
                    }
                    if (next != null && (set == null || set.songs.none { it.id == next.id }))
                        Text((if (set != null) "Then " else "") + "${next.artist} — ${next.title}", style = MaterialTheme.typography.bodyMedium)
                    if (set == null && next == null && station.llmReady) Text(
                        if (station.enabled) "The DJ is lining up songs from your library…" else "Turn on “Picks music” and the DJ builds sets from your saved songs.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (station.llmReady) ThemeField(station.theme)
                }
            }
        }
    }
}

@Composable
private fun ThemeField(current: String) {
    val repo = repo()
    val focus = LocalFocusManager.current
    var theme by remember(current) { mutableStateOf(current) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(theme, { theme = it }, Modifier.weight(1f), label = { Text("Station theme") }, singleLine = true,
            placeholder = { Text("A varied mix from my saved music") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); repo.djControl(buildJsonObject { put("theme", theme) }) }))
        TextButton(onClick = { focus.clearFocus(); repo.djControl(buildJsonObject { put("theme", theme) }) }, enabled = theme.isNotBlank() && theme != current) { Text("Set") }
    }
}

@Composable
private fun DjSwitch(label: String, hint: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 4.dp).semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

/** Where the server plays: its own speakers, listening devices, or both. */
@Composable
private fun OutputCard(snapshot: Snapshot) {
    val repo = repo()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val outputs = listOf("browser" to "Devices", "host" to "Host speakers", "both" to "Both")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text("Audio output · " + (outputs.firstOrNull { it.first == snapshot.state.output }?.second ?: snapshot.state.output),
                    modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        outputs.forEachIndexed { index, (value, name) ->
                            SegmentedButton(selected = snapshot.state.output == value, onClick = { repo.setOutput(value) },
                                shape = SegmentedButtonDefaults.itemShape(index, outputs.size), enabled = !snapshot.pending) { Text(name, maxLines = 1) }
                        }
                    }
                    Text("“Devices” plays in browsers and phones that are listening. Manage them in More → Listening devices.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Downloaded songs playing on this phone without the server. */
@Composable
private fun LocalNowPlaying(phone: PhonePlayback) {
    val library = phone()
    val context = LocalContext.current
    val tracks by library.tracks.collectAsStateWithLifecycle()
    val track = tracks.firstOrNull { it.id == phone.localTrack }
    val player = Playback.local()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 560.dp)) {
            Eyebrow("Downloaded · playing on this phone")
            Spacer(Modifier.height(12.dp))
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Artwork(null, minOf(maxWidth, 420.dp), corner = 10.dp, model = track?.let { library.cover(it.id) })
            }
            Spacer(Modifier.height(20.dp))
            Text(track?.label ?: "", style = MaterialTheme.typography.headlineMedium)
            Text(track?.artist ?: "", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!track?.album.isNullOrEmpty()) Text(track!!.album, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            var dragging by remember { mutableStateOf<Float?>(null) }
            val fraction = if (phone.durationMs > 0) phone.positionMs.toFloat() / phone.durationMs else 0f
            Slider(value = dragging ?: fraction, onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { player?.seekTo((it * phone.durationMs).toLong()) }; dragging = null })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(time((phone.positionMs / 1000).toInt()), style = MaterialTheme.typography.bodySmall)
                Text(time((phone.durationMs / 1000).toInt()), style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player?.seekToPreviousMediaItem() }) { Icon(Icons.Rounded.SkipPrevious, "Previous song", Modifier.size(32.dp)) }
                FilledIconButton(onClick = { player?.let { if (it.isPlaying) it.pause() else it.play() } }, modifier = Modifier.size(72.dp),
                    shape = RoundedCornerShape(18.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Palette.Orange, contentColor = Palette.PlayerDark)) {
                    Icon(if (phone.localPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (phone.localPlaying) "Pause" else "Play", Modifier.size(36.dp))
                }
                IconButton(onClick = { player?.seekToNextMediaItem() }) { Icon(Icons.Rounded.SkipNext, "Next song", Modifier.size(32.dp)) }
            }
            Text("Song ${phone.localIndex + 1} of ${phone.localQueue.size}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { Playback.listen(context) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Headphones, null); Spacer(Modifier.width(8.dp)); Text("Back to the radio")
            }
            Spacer(Modifier.height(8.dp))
            Text("Downloads play right here, with or without a connection. “Back to the radio” stops them and listens to your server again.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
