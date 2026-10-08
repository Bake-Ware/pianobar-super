package org.pianobarsuper.app.data

import android.content.Context
import coil.ImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import org.pianobarsuper.app.net.Api
import org.pianobarsuper.app.net.ClientsResponse
import org.pianobarsuper.app.net.LibraryResponse
import org.pianobarsuper.app.net.LoginRequired
import org.pianobarsuper.app.net.Playlist
import org.pianobarsuper.app.net.PlaylistsResponse
import org.pianobarsuper.app.net.PlaylistRun
import org.pianobarsuper.app.net.Snapshot
import org.pianobarsuper.app.net.Song
import org.pianobarsuper.app.net.VoicesResponse
import org.pianobarsuper.app.net.json
import java.io.File
import java.io.IOException

sealed interface Connection {
    data object NoServer : Connection
    data object Connecting : Connection
    data object Online : Connection
    data object SignInRequired : Connection
    data class Unreachable(val message: String) : Connection
}

/**
 * Everything the screens show comes from here. A long poll on /api/state runs
 * while the app is visible or the phone is playing audio; the library and
 * playlists are cached on disk so they can be browsed (and downloaded songs
 * played) without the server.
 */
class Repository(private val app: Context) {
    val store = ServerStore(app)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _api = MutableStateFlow(store.origin?.let { newApi(it) })
    val api: StateFlow<Api?> = _api.asStateFlow()

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()

    private val _connection = MutableStateFlow<Connection>(if (store.origin == null) Connection.NoServer else Connection.Connecting)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    /** Short messages for a snackbar. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    private val cacheDir = File(app.filesDir, "server-cache").apply { mkdirs() }
    private val _library = MutableStateFlow(readCache<LibraryResponse>("library.json")?.songs ?: emptyList())
    val library: StateFlow<List<Song>> = _library.asStateFlow()
    private var libraryText = ""

    private val _playlists = MutableStateFlow(readCache<PlaylistsResponse>("playlists.json")?.playlists ?: emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _busy = MutableStateFlow(false)
    /** A command is on its way to the server. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    var imageLoader: ImageLoader = buildImageLoader(_api.value)
        private set

    private var watchJob: Job? = null
    private var demand = 0

    private fun newApi(origin: String) = Api(origin, WebViewCookies) { store.basicAuthorization() }

    private fun buildImageLoader(api: Api?) = ImageLoader.Builder(app)
        .okHttpClient(api?.client ?: okhttp3.OkHttpClient())
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()

    // ---- Connection ------------------------------------------------------------------------

    fun setServer(origin: String) {
        if (origin != store.origin) {
            store.server = origin
            _snapshot.value = null
            _library.value = emptyList(); libraryText = ""
            _playlists.value = emptyList()
            cacheDir.listFiles()?.forEach { it.delete() }
        }
        reconnect()
    }

    /** Rebuild the client (after sign-in or a server change) and poll again immediately. */
    fun reconnect() {
        val api = store.origin?.let { newApi(it) }
        _api.value = api
        imageLoader = buildImageLoader(api)
        _connection.value = if (api == null) Connection.NoServer else Connection.Connecting
        restartWatch()
    }

    /** Screens and the playback service hold the live connection open while they need it. */
    fun retain() { demand++; if (demand == 1) restartWatch() }
    fun release() { demand = (demand - 1).coerceAtLeast(0); if (demand == 0) { watchJob?.cancel(); watchJob = null } }

    private fun restartWatch() {
        watchJob?.cancel()
        if (demand == 0) return
        val api = _api.value ?: return
        watchJob = scope.launch {
            var revision = -1
            var backoff = 1000L
            while (true) {
                try {
                    val element = api.get("api/state?after=$revision")
                    val snapshot = json.decodeFromJsonElement<Snapshot>(element)
                    revision = snapshot.revision
                    _snapshot.value = snapshot
                    _connection.value = Connection.Online
                    backoff = 1000L
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LoginRequired) {
                    _connection.value = Connection.SignInRequired
                    return@launch
                } catch (e: Exception) {
                    _connection.value = Connection.Unreachable(e.message ?: "Can’t reach the server.")
                    revision = -1
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(15_000L)
                }
            }
        }
    }

    // ---- Commands --------------------------------------------------------------------------

    /** Run a call, turning server errors into a message; null on failure. */
    suspend fun <T> attempt(block: suspend (Api) -> T): T? {
        val api = _api.value ?: run { messages.tryEmit("Choose your server first."); return null }
        return try {
            block(api)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LoginRequired) {
            _connection.value = Connection.SignInRequired
            messages.tryEmit("Sign in to your server again.")
            null
        } catch (e: IOException) {
            messages.tryEmit(e.message ?: "Can’t reach the server.")
            null
        } catch (e: Exception) {
            messages.tryEmit(e.message ?: "Something went wrong.")
            null
        }
    }

    suspend fun command(body: JsonObject): Boolean {
        _busy.value = true
        try {
            return attempt { it.post("api/command", body) } != null
        } finally { _busy.value = false }
    }

    fun launchCommand(body: JsonObject) { scope.launch { command(body) } }
    fun action(id: String) = launchCommand(buildJsonObject { put("action", id) })
    fun playSaved(id: String) = launchCommand(buildJsonObject { put("playSaved", id) })
    fun selectStation(id: String) = launchCommand(buildJsonObject { put("selectStation", id) })
    fun setOutput(output: String) = launchCommand(buildJsonObject { put("output", output) })
    fun answer(text: String, promptId: Int) = launchCommand(buildJsonObject { put("text", text); put("promptId", promptId) })

    /** Play songs in order: the first now, the rest queued once it has started (the player holds 30). */
    fun playSongs(ids: List<String>) {
        if (ids.isEmpty()) return
        scope.launch {
            if (!command(buildJsonObject { put("playSaved", ids[0]) }) || ids.size == 1) return@launch
            val rest = ids.drop(1).take(30)
            repeat(40) {
                val state = _snapshot.value
                if (state != null && state.state.savedId == ids[0] && !state.pending) {
                    command(buildJsonObject {
                        put("queueSaved", JsonArray(rest.map { JsonPrimitive(it) }))
                        put("expectedSongKey", state.state.songKey)
                    })
                    return@launch
                }
                delay(500)
            }
        }
    }

    // ---- DJ --------------------------------------------------------------------------------

    fun djControl(change: JsonObject) = scope.launch { attempt { it.post("api/dj/control", change) } }
    suspend fun djAnnounce(text: String) = attempt { it.post("api/dj/announce", buildJsonObject { put("text", text) }) } != null
    suspend fun djIntroduce() = attempt { it.post("api/dj/introduce", JsonObject(emptyMap())) } != null
    suspend fun djVoices(): VoicesResponse? = attempt { json.decodeFromJsonElement<VoicesResponse>(it.get("api/dj/voices")) }
    suspend fun voiceSample(text: String, voice: String): ByteArray? = attempt {
        it.bytes("api/voice", buildJsonObject { put("text", text); put("voice", voice) }).first
    }

    suspend fun djPlaylist(description: String, count: Int): Playlist? = attempt {
        json.decodeFromJsonElement<Playlist>(it.post("api/dj/playlist", buildJsonObject {
            put("description", description); put("count", count)
        }, slow = true))
    }?.also { upsertPlaylist(it) }

    // ---- Library and playlists -------------------------------------------------------------

    suspend fun refreshLibrary(quiet: Boolean = true) {
        val api = _api.value ?: return
        try {
            val text = withContext(Dispatchers.IO) {
                api.client.newCall(api.request("api/library").build()).execute().use { response ->
                    Api.check(response); response.body?.string() ?: ""
                }
            }
            if (text == libraryText) return
            libraryText = text
            _library.value = json.decodeFromString<LibraryResponse>(text).songs
            writeCache("library.json", text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is LoginRequired) _connection.value = Connection.SignInRequired
            if (!quiet) messages.tryEmit(e.message ?: "Could not read your saved songs.")
        }
    }

    suspend fun refreshPlaylists() {
        attempt { json.decodeFromJsonElement<PlaylistsResponse>(it.get("api/playlists")) }?.let {
            _playlists.value = it.playlists
            writeCache("playlists.json", json.encodeToString(it))
        }
    }

    private fun upsertPlaylist(playlist: Playlist) {
        val list = _playlists.value.toMutableList()
        val index = list.indexOfFirst { it.id == playlist.id }
        if (index >= 0) list[index] = playlist else list.add(playlist)
        _playlists.value = list
        writeCache("playlists.json", json.encodeToString(PlaylistsResponse(list)))
    }

    suspend fun savePlaylist(id: String? = null, name: String? = null, description: String? = null, songs: List<String>? = null): Playlist? =
        attempt {
            json.decodeFromJsonElement<Playlist>(it.post("api/playlists/save", buildJsonObject {
                id?.let { v -> put("id", v) }
                name?.let { v -> put("name", v) }
                description?.let { v -> put("description", v) }
                songs?.let { v -> put("songs", JsonArray(v.map { s -> JsonPrimitive(s) })) }
            }))
        }?.also { upsertPlaylist(it) }

    suspend fun addToPlaylist(id: String, songs: List<String>): Playlist? = attempt {
        json.decodeFromJsonElement<Playlist>(it.post("api/playlists/add", buildJsonObject {
            put("id", id); put("songs", JsonArray(songs.map { s -> JsonPrimitive(s) }))
        }))
    }?.also { upsertPlaylist(it) }

    suspend fun deletePlaylist(id: String): Boolean = (attempt { it.post("api/playlists/delete", buildJsonObject { put("id", id) }) } != null)
        .also { if (it) { _playlists.value = _playlists.value.filter { p -> p.id != id }; writeCache("playlists.json", json.encodeToString(PlaylistsResponse(_playlists.value))) } }

    suspend fun playPlaylist(id: String, start: Int = 0, shuffle: Boolean = false): PlaylistRun? = attempt {
        json.decodeFromJsonElement<PlaylistRun>(it.post("api/playlists/play", buildJsonObject {
            put("id", id); if (start > 0) put("start", start); if (shuffle) put("shuffle", true)
        }))
    }

    suspend fun stopPlaylist() = attempt { it.post("api/playlists/stop", JsonObject(emptyMap())) } != null

    // ---- Settings and listening devices ----------------------------------------------------

    suspend fun loadSettings(): JsonObject? = attempt { it.get("api/settings").jsonObject }

    suspend fun saveSettings(changes: JsonObject, apply: Boolean): Boolean =
        attempt { it.post("api/settings", buildJsonObject { put("settings", changes); put("apply", apply) }) } != null

    suspend fun clients(): ClientsResponse? = attempt { json.decodeFromJsonElement<ClientsResponse>(it.get("api/clients")) }
    suspend fun updateClient(change: JsonObject) = attempt { it.post("api/clients/update", change) } != null
    suspend fun routeClients(route: JsonObject) = attempt { it.post("api/clients/route", route) } != null

    /** Absolute URL for a server path such as /api/artwork/…, or a remote cover as is. */
    fun artUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("https://") || path.startsWith("http://")) return path
        val origin = store.origin ?: return null
        return origin + path.removePrefix("/")
    }

    // ---- Disk cache ------------------------------------------------------------------------

    private inline fun <reified T> readCache(name: String): T? = try {
        json.decodeFromString<T>(File(cacheDir, name).readText())
    } catch (e: Exception) { null }

    private fun writeCache(name: String, text: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val target = File(cacheDir, name)
                val temporary = File(cacheDir, "$name.tmp")
                temporary.writeText(text)
                temporary.renameTo(target)
            } catch (e: IOException) { /* The cache is only a convenience. */ }
        }
    }
}
