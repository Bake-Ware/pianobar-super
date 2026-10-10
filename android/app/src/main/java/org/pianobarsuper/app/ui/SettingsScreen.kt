package org.pianobarsuper.app.ui

import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.roundToInt

/**
 * The server's settings, as on the web: one form across five tabs. Values are
 * keyed "user", "web.port", "dj.voice"…; only changed values are sent.
 */
private sealed interface Field { val key: String; val label: String; val hint: String }
private data class TextField(override val key: String, override val label: String, override val hint: String = "",
                             val secret: Boolean = false, val number: IntRange? = null, val multiline: Boolean = false,
                             val decimal: ClosedFloatingPointRange<Double>? = null) : Field
private data class Toggle(override val key: String, override val label: String, override val hint: String = "") : Field
private data class Level(override val key: String, override val label: String, val range: IntRange,
                         val format: (Int) -> String, override val hint: String = "", val default: Int = 0) : Field
private data class Choice(override val key: String, override val label: String, val options: List<Pair<String, String>>, override val hint: String = "") : Field

private val tabs = listOf("Listening", "Pandora", "DJ", "Connections", "Web access")

private val restart = "Applies after Save & restart."

private val sections: List<List<Pair<String, List<Field>>>> = listOf(
    listOf(
        "Sound" to listOf(
            Choice("web.output", "Default audio output", listOf("host" to "Host speakers", "browser" to "Listening devices", "both" to "Both")),
            Choice("audio_quality", "Audio quality", listOf("low" to "Low", "medium" to "Medium", "high" to "High"), restart),
            TextField("audio_buffer_ms", "Host speaker buffer (ms)", "Higher rides out hiccups on the server’s own speakers. $restart", number = 0..2000),
        ),
        "Offline library" to listOf(
            TextField("cache_dir", "Library folder", "An absolute path on the server, or one starting with ~/. $restart"),
            Toggle("cache_songs", "Save songs automatically as they play"),
            Toggle("offline_fallback", "Switch to saved songs if the connection drops"),
            Toggle("offline", "Start in offline mode", restart),
        ),
    ),
    listOf(
        "Account" to listOf(
            TextField("user", "Email", "Your Pandora sign-in. $restart"),
            TextField("password", "Password", "Leave blank to keep the saved password.", secret = true),
            Toggle("clearPassword", "Forget saved Pandora credentials"),
        ),
    ),
    listOf(
        "Personality" to listOf(
            TextField("dj.dj_name", "DJ name"), TextField("dj.listener_name", "Your name"),
            TextField("dj.style", "Personality", "How the DJ talks. Applies right away.", multiline = true),
        ),
        "Talking" to listOf(
            Choice("dj.voice", "Voice", emptyList(), "Voices come from the voice server under Connections."),
            Toggle("dj.play_over_music", "Talk over the music", "The music dips while the DJ speaks. Off: the music pauses for the DJ instead."),
            Toggle("dj.emotes", "Expressive sounds", "Lets the DJ laugh, chuckle, sigh or gasp mid-line. Works with Chatterbox voices."),
            Level("dj.voice_level", "Voice level", -10..10, { if (it == 0) "0 dB" else "%+d dB".format(it).replace("-", "−") },
                "Every line is evened out to one loudness; this moves the DJ from there."),
            Level("dj.duck_depth", "Music dip", -30..0, { if (it == 0) "None" else "−${-it} dB" },
                "How far the song drops while the DJ talks over it. Both apply from the next line.", default = -12),
        ),
        "Picking music" to listOf(
            TextField("dj.theme", "Default station theme", multiline = true),
            Choice("dj.set_mode", "Set size", listOf("songs" to "By songs", "minutes" to "By minutes")),
            TextField("dj.set_songs", "Songs per set", number = 1..30),
            TextField("dj.set_minutes", "Minutes per set", number = 1..120),
        ),
        "Station hopping" to listOf(TextField("dj.hop_songs", "Songs per station", "How many songs before hopping to a random station.", number = 1..20)),
        "Weather & time" to listOf(
            TextField("dj.weather_place", "Place name", "The DJ knows the local time and can check the weather here (Open-Meteo)."),
            TextField("dj.weather_lat", "Latitude", decimal = -90.0..90.0),
            TextField("dj.weather_lon", "Longitude", decimal = -180.0..180.0),
        ),
    ),
    listOf(
        "Language model" to listOf(
            TextField("dj.llm_url", "Endpoint URL", "An OpenAI-compatible chat completions URL."),
            TextField("dj.model", "Model"),
            TextField("dj.llm_key", "Bearer key", "Leave blank to keep the saved key.", secret = true),
            Toggle("dj.clear_llm_key", "Forget the saved key"),
        ),
        "Voice" to listOf(
            TextField("dj.tts_url", "Endpoint URL"),
            TextField("dj.tts_key", "Bearer key", "Leave blank to keep the saved key.", secret = true),
            TextField("dj.tts_ca", "HTTPS certificate file", "An absolute path on the server, for a self-signed voice server."),
            Toggle("dj.clear_tts_key", "Forget the saved key"),
        ),
        "Song details" to listOf(Toggle("dj.metadata_network", "Look up genres and artwork on MusicBrainz", restart)),
    ),
    listOf(
        "Web access" to listOf(
            TextField("web.listen", "Listen address", "127.0.0.1 keeps the server private to its machine; 0.0.0.0 opens it to your network. Restart pianobar to apply."),
            TextField("web.port", "Port", number = 1..65535),
            TextField("web.password", "Web password", "Leave blank to keep the current one.", secret = true),
        ),
    ),
)

/** One of the DJ's MCP servers as edited here; a blank auth keeps the saved one. */
private data class McpServer(val id: String? = null, val name: String = "", val url: String = "", val hint: String = "",
                             val enabled: Boolean = true, val authSet: Boolean = false, val auth: String = "", val clearAuth: Boolean = false) {
    fun json() = buildJsonObject {
        id?.let { put("id", JsonPrimitive(it)) }
        put("name", JsonPrimitive(name.trim())); put("url", JsonPrimitive(url.trim()))
        put("hint", JsonPrimitive(hint)); put("enabled", JsonPrimitive(enabled))
        if (auth.isNotEmpty()) put("auth", JsonPrimitive(auth))
        if (clearAuth) put("clear_auth", JsonPrimitive(true))
    }
}

private fun JsonObject.lookup(key: String): JsonElement? {
    val parts = key.split(".")
    return if (parts.size == 1) this[key] else (this[parts[0]] as? JsonObject)?.get(parts[1])
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(back: () -> Unit) {
    val repo = repo()
    val scope = rememberCoroutineScope()
    val snapshot by repo.snapshot.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var baseline by remember { mutableStateOf<JsonObject?>(null) }
    val values = remember { mutableStateMapOf<String, String>() }
    var status by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var voices by remember { mutableStateOf(listOf<String>()) }
    val mcp = remember { mutableStateListOf<McpServer>() }
    var mcpDirty by remember { mutableStateOf(false) }
    fun load() = scope.launch {
        val loaded = repo.loadSettings() ?: return@launch
        baseline = loaded
        mcp.clear()
        (loaded.lookup("dj.mcp_servers") as? JsonArray)?.forEach { item ->
            val server = item as? JsonObject ?: return@forEach
            fun text(key: String) = server[key]?.jsonPrimitive?.contentOrNull ?: ""
            mcp.add(McpServer(text("id"), text("name"), text("url"), text("hint"), server["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                server["authSet"]?.jsonPrimitive?.booleanOrNull ?: false))
        }
        mcpDirty = false
        values.clear()
        for (field in sections.flatten().flatMap { it.second }) {
            val element = loaded.lookup(field.key)
            values[field.key] = when (field) {
                is Toggle -> (element?.jsonPrimitive?.booleanOrNull ?: false).toString()
                else -> if (field is TextField && field.secret) "" else element?.jsonPrimitive?.contentOrNull ?: ""
            }
        }
        voices = repo.djVoices()?.voices ?: emptyList()
    }
    LaunchedEffect(Unit) { load() }
    val overrides = remember(baseline) { (baseline?.get("dj") as? JsonObject)?.get("overrides")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet() }
    fun changes(): JsonObject {
        val base = baseline ?: return JsonObject(emptyMap())
        val top = mutableMapOf<String, JsonElement>()
        val nested = mutableMapOf<String, MutableMap<String, JsonElement>>()
        for (field in sections.flatten().flatMap { it.second }) {
            val raw = values[field.key] ?: continue
            val old = base.lookup(field.key)
            val value: JsonElement = when {
                field is Toggle -> JsonPrimitive(raw.toBoolean())
                field is TextField && field.number != null -> JsonPrimitive(raw.toIntOrNull() ?: continue)
                field is TextField && field.decimal != null -> JsonPrimitive(raw.toDoubleOrNull()?.takeIf { it in field.decimal } ?: continue)
                field is Level -> JsonPrimitive(raw.toIntOrNull() ?: continue)
                else -> JsonPrimitive(raw)
            }
            if (field is TextField && field.secret) { if (raw.isEmpty()) continue }
            else if (field is Toggle && (field.key.endsWith("clearPassword") || field.key.contains("clear_"))) { if (!raw.toBoolean()) continue }
            else if (field is TextField && field.decimal != null) { if (value.jsonPrimitive.doubleOrNull == old?.jsonPrimitive?.doubleOrNull) continue }
            else if (value == old || (old?.jsonPrimitive?.intOrNull != null && value.jsonPrimitive.intOrNull == old.jsonPrimitive.intOrNull && (field is Level || field is TextField && field.number != null))) continue
            val parts = field.key.split(".")
            if (parts.size == 2) {
                if (parts[0] == "dj" && parts[1] in overrides) continue
                nested.getOrPut(parts[0]) { mutableMapOf() }[parts[1]] = value
            } else top[field.key] = value
        }
        if (mcpDirty) nested.getOrPut("dj") { mutableMapOf() }["mcp_servers"] = JsonArray(mcp.map { it.json() })
        nested.forEach { (k, v) -> top[k] = JsonObject(v) }
        return JsonObject(top)
    }
    fun save(apply: Boolean) {
        val change = changes()
        if (change.isEmpty() && !apply) { status = "Nothing to save."; return }
        saving = true
        scope.launch {
            val ok = repo.saveSettings(change, apply)
            saving = false
            status = if (!ok) "Not saved. Check the highlighted message and try again." else if (apply)
                "Saved. Starting the player… Web address and password changes apply after restarting pianobar."
            else "Saved. Names, voice, personality and theme are active now. Restart the player for account, listening, provider and metadata changes."
            if (ok) load()
        }
    }
    SubPage("Server settings", back) {
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            tabs.forEachIndexed { index, name -> Tab(tab == index, { tab = index }, text = { Text(name) }) }
        }
        if (baseline == null) { Text("Loading settings…", Modifier.padding(16.dp)); return@SubPage }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((title, fields) in sections[tab]) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                for (field in fields) {
                    val hidden = (field.key == "dj.set_songs" && values["dj.set_mode"] == "minutes") || (field.key == "dj.set_minutes" && values["dj.set_mode"] != "minutes")
                    if (hidden) continue
                    val locked = field.key.startsWith("dj.") && field.key.removePrefix("dj.") in overrides
                    FieldEditor(field, values[field.key] ?: "", if (field.key == "dj.voice") voices else null, !locked) { values[field.key] = it }
                    if (locked) Text("Set by an environment variable on the server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
            if (tab == 2) McpServers(mcp) { index, server ->
                if (server == null) mcp.removeAt(index) else if (index == mcp.size) mcp.add(server) else mcp[index] = server
                mcpDirty = true
            }
            if (tab == 2) TryTheDj(values["dj.voice"] ?: "", snapshot?.djStation?.voiceReady == true, snapshot?.djStation?.llmReady == true)
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                OutlinedButton(onClick = { save(false) }, enabled = !saving) { Text("Save") }
                Button(onClick = { save(true) }, enabled = !saving && (snapshot?.state?.cachePending ?: 0) == 0 && snapshot?.restarting != true,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Orange, contentColor = Palette.PlayerDark)) {
                    Text(if (snapshot?.setupRequired == true) "Save & start listening" else "Save & restart player")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldEditor(field: Field, value: String, voices: List<String>?, enabled: Boolean, onChange: (String) -> Unit) {
    when (field) {
        is Toggle -> Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange((!value.toBoolean()).toString()) }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(field.label, style = MaterialTheme.typography.bodyLarge)
                if (field.hint.isNotEmpty()) Text(field.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(value.toBoolean(), { onChange(it.toString()) }, enabled = enabled)
        }
        is Choice -> {
            val options = voices?.let { list -> listOf("" to "Use the voice server’s default") + list.map { it to it } + (if (value.isNotEmpty() && value !in list) listOf(value to value) else emptyList()) } ?: field.options
            var open by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(open, { if (enabled) open = it }) {
                OutlinedTextField(options.firstOrNull { it.first == value }?.second ?: value, {}, readOnly = true, enabled = enabled, label = { Text(field.label) },
                    supportingText = if (field.hint.isNotEmpty()) ({ Text(field.hint) }) else null,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
                ExposedDropdownMenu(open, { open = false }) {
                    options.forEach { (v, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onChange(v); open = false }) }
                }
            }
        }
        is Level -> Column(Modifier.fillMaxWidth()) {
            val level = (value.toIntOrNull() ?: field.default).coerceIn(field.range)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(field.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(field.format(level), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(level.toFloat(), { onChange(it.roundToInt().toString()) }, enabled = enabled,
                valueRange = field.range.first.toFloat()..field.range.last.toFloat())
            if (field.hint.isNotEmpty()) Text(field.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is TextField -> OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled, label = { Text(field.label) },
            supportingText = if (field.hint.isNotEmpty() || field.number != null) ({
                Text(listOf(field.hint, field.number?.let { "${it.first}–${it.last}" } ?: "").filter { it.isNotEmpty() }.joinToString(" "))
            }) else null,
            isError = field.number != null && value.isNotEmpty() && value.toIntOrNull()?.let { it in field.number } != true ||
                field.decimal != null && value.toDoubleOrNull()?.let { it in field.decimal } != true,
            singleLine = !field.multiline, minLines = if (field.multiline) 2 else 1,
            visualTransformation = if (field.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = when { field.secret -> KeyboardType.Password; field.number != null -> KeyboardType.Number
                field.decimal != null -> KeyboardType.Decimal; else -> KeyboardType.Text }))
    }
}

/** Settings → DJ → Tool servers: MCP servers the DJ may consult while writing intros. */
@Composable
private fun McpServers(servers: List<McpServer>, edit: (Int, McpServer?) -> Unit) {
    Text("Tool servers (MCP)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text("Servers the DJ may call while writing intros. The note tells the DJ what each is good for. Auth stays on the server.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    servers.forEachIndexed { index, server ->
        Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.small) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(server.name, { edit(index, server.copy(name = it.take(60))) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                OutlinedTextField(server.url, { edit(index, server.copy(url = it)) }, Modifier.fillMaxWidth(), label = { Text("Endpoint URL") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(server.auth, { edit(index, server.copy(auth = it)) }, Modifier.fillMaxWidth(), label = { Text("Auth") }, singleLine = true,
                    supportingText = { Text(if (server.authSet) "Saved. Leave blank to keep it." else "Optional bearer token or full header value.") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(server.hint, { edit(index, server.copy(hint = it)) }, Modifier.fillMaxWidth(), label = { Text("Note for the DJ") },
                    placeholder = { Text("See what's happening in Rook world and riff on it.") }, minLines = 2)
                FieldEditor(Toggle("enabled", "Enabled"), server.enabled.toString(), null, true) { edit(index, server.copy(enabled = it.toBoolean())) }
                if (server.authSet) FieldEditor(Toggle("clear_auth", "Forget saved auth"), server.clearAuth.toString(), null, true) {
                    edit(index, server.copy(clearAuth = it.toBoolean()))
                }
                OutlinedButton(onClick = { edit(index, null) }) { Text("Remove") }
            }
        }
    }
    if (servers.size < 8) OutlinedButton(onClick = { edit(servers.size, McpServer()) }) { Text("Add server") }
    HorizontalDivider()
}

/** Settings → DJ → Try your DJ: hear a voice, speak a line, or ask for an intro now. */
@Composable
private fun TryTheDj(voice: String, voiceReady: Boolean, llmReady: Boolean) {
    if (!voiceReady && !llmReady) return
    val repo = repo()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var line by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    Text("Try your DJ", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    if (voiceReady) {
        OutlinedTextField(line, { line = it }, Modifier.fillMaxWidth(), label = { Text("Give the DJ a line") }, placeholder = { Text("Shout-out to everyone in the kitchen!") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { if (repo.djAnnounce(line)) { note = "On its way to every speaker."; line = "" } } }, enabled = line.isNotBlank()) { Text("Say it on air") }
            OutlinedButton(onClick = {
                scope.launch {
                    note = "Fetching a sample…"
                    val bytes = repo.voiceSample("Hi, this is how I sound on your radio.", voice) ?: run { note = ""; return@launch }
                    val file = File(context.cacheDir, "voice-sample").apply { writeBytes(bytes) }
                    player?.release()
                    player = MediaPlayer().apply { setDataSource(file.absolutePath); setOnCompletionListener { note = "" }; prepare(); start() }
                    note = "Playing a sample on this phone."
                }
            }) { Text("Sample this voice") }
        }
    }
    if (llmReady && voiceReady) OutlinedButton(onClick = { scope.launch { if (repo.djIntroduce()) note = "Writing an intro for this song…" } }) { Text("Introduce this song") }
    if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.width(0.dp))
}
