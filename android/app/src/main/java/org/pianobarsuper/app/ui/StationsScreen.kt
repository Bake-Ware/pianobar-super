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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.pianobarsuper.app.net.stationActionIds
import org.pianobarsuper.app.net.title

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StationsScreen() {
    val repo = repo()
    val focus = LocalFocusManager.current
    val snapshot by repo.snapshot.collectAsStateWithLifecycle()
    val state = snapshot?.state
    var query by rememberSaveable { mutableStateOf("") }
    val stations = state?.stations ?: emptyList()
    val filtered = remember(stations, query) { stations.filter { it.name.contains(query.trim(), ignoreCase = true) } }
    val canChange = state != null && !state.offline && state.can("act_stationchange") && snapshot?.pending == false
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                Eyebrow("Find your next favorite")
                Text("Stations", style = MaterialTheme.typography.headlineLarge)
                Text(
                    when {
                        state == null -> "Connecting…"
                        state.offline -> "Reconnect to browse and manage your Pandora stations."
                        state.station.isNotEmpty() -> "Listening to ${state.station}."
                        else -> "Choose a station or discover something new."
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                if (state?.offline == true) Button(onClick = { repo.action("act_offline") }, enabled = state.can("act_offline")) { Text("Reconnect to Pandora") }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search your stations") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true,
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        // With one match left, Go plays it.
                        if (filtered.size == 1 && canChange) { repo.selectStation(filtered[0].id); focus.clearFocus() }
                    }))
                if (query.isNotEmpty()) Text(if (filtered.isEmpty()) "No stations match." else "${filtered.size} of ${stations.size} stations" +
                    if (filtered.size == 1) " · press Go to play it" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        items(filtered, key = { it.id }) { station ->
            val current = station.id == state?.stationId
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable(enabled = canChange && !current) { repo.selectStation(station.id) }
                .padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (current) Icons.Rounded.Equalizer else if (station.quickMix) Icons.Rounded.Shuffle else Icons.Rounded.Radio, null,
                    tint = if (current) Palette.Orange else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(station.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (current) Palette.Orange else MaterialTheme.colorScheme.onSurface)
                    if (current || station.quickMix) Text(listOfNotNull("Playing now".takeIf { current }, "QuickMix".takeIf { station.quickMix }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { menu = true }, enabled = canChange) { Icon(Icons.Rounded.MoreVert, "More for ${station.name}") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Play now") }, enabled = !current, onClick = { menu = false; repo.selectStation(station.id) })
                    DropdownMenuItem(text = { Text("Play after this song") }, enabled = !current, onClick = {
                        menu = false; repo.launchCommand(buildJsonObject { put("queueStation", station.id) })
                    })
                }
            }
        }
        item {
            HorizontalDivider(Modifier.padding(top = 16.dp))
            SectionTitle("Manage stations")
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                stationActionIds.mapNotNull { state?.action(it) }.forEach { action ->
                    AssistChip(onClick = { repo.action(action.id) }, enabled = action.enabled && snapshot?.pending == false, label = { Text(action.title()) })
                }
            }
            Text("These open a short question from the player, such as a name or a choice.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
    }
}
