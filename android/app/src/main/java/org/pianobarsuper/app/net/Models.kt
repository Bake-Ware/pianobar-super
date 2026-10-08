package org.pianobarsuper.app.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** GET /api/state: one revision of everything the player is doing. */
@Serializable
data class Snapshot(
    val revision: Int = -1,
    val state: PlayerState = PlayerState(),
    val prompt: Prompt = Prompt(),
    val output: String = "",
    val pending: Boolean = false,
    val exited: Boolean = false,
    val playerStopped: Boolean = false,
    val setupRequired: Boolean = false,
    val restarting: Boolean = false,
    val djStation: DjStation? = null,
    val djVoice: DjVoice? = null,
    val playlist: PlaylistRun? = null,
)

@Serializable
data class PlayerState(
    val offline: Boolean = false,
    val volume: Int = 0,
    val output: String = "",
    val paused: Boolean = false,
    val elapsed: Int = 0,
    val duration: Int = 0,
    val station: String = "",
    val stationId: String = "",
    val nextStationId: String = "",
    val stations: List<Station> = emptyList(),
    val cacheDir: String = "",
    val cachePending: Int = 0,
    val savedId: String = "",
    val queuedSavedId: String = "",
    val queuedTitle: String = "",
    val queuedArtist: String = "",
    val queuedSavedIds: List<String> = emptyList(),
    val nextTitle: String = "",
    val nextArtist: String = "",
    val nextAlbum: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val cover: String = "",
    val cachedCover: String = "",
    val loved: Boolean = false,
    val actions: List<PlayerAction> = emptyList(),
    val metadata: SongMetadata? = null,
    val songKey: String = "",
) {
    fun action(id: String) = actions.firstOrNull { it.id == id }
    fun can(id: String) = action(id)?.enabled == true
}

@Serializable
data class Station(val id: String = "", val name: String = "", val quickMix: Boolean = false)

@Serializable
data class PlayerAction(val id: String = "", val label: String = "", val key: String = "", val enabled: Boolean = false)

@Serializable
data class SongMetadata(
    val status: String = "",
    val genres: List<String> = emptyList(),
    val genreScope: String = "",
    val releaseDate: String = "",
    val recordingId: String = "",
    val cover: String = "",
)

@Serializable
data class Prompt(
    val active: Boolean = false,
    val id: Int = 0,
    val secret: Boolean = false,
    val line: Boolean = false,
    val limit: Int = 500,
    val mask: String = "",
    val kind: String = "",
    val station: String = "",
)

@Serializable
data class DjStation(
    val enabled: Boolean = false,
    val theme: String = "",
    val status: String = "off",
    val next: SongRef? = null,
    val error: String = "",
    val style: String = "",
    val talk: Boolean = false,
    val hop: Boolean = false,
    val hopSongs: Int = 4,
    val hopCount: Int = 0,
    val hopNext: JsonElement? = null,
    val settings: DjNames = DjNames(),
    val currentSet: DjSet? = null,
    val llmReady: Boolean = false,
    val voiceReady: Boolean = false,
)

@Serializable
data class DjNames(val dj_name: String = "", val listener_name: String = "")

@Serializable
data class DjSet(val songs: List<SongRef> = emptyList(), val position: Int = 0, val duration: Int = 0)

@Serializable
data class SongRef(val id: String = "", val title: String = "", val artist: String = "", val duration: Int = 0)

@Serializable
data class DjVoice(val id: Int = 0, val status: String = "idle", val text: String = "", val songKey: String = "", val error: String = "")

@Serializable
data class PlaylistRun(val id: String = "", val name: String = "", val position: Int = 0, val total: Int = 0, val shuffle: Boolean = false)

/** A song saved in the server's offline library. */
@Serializable
data class Song(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val cover: String = "",
    val duration: Int = 0,
    val genres: List<String> = emptyList(),
) {
    val label get() = title.ifBlank { "Untitled song" }
}

@Serializable
data class LibraryResponse(val songs: List<Song> = emptyList())

@Serializable
data class Playlist(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val songs: List<String> = emptyList(),
    val by: String = "you",
    val created: Long = 0,
    val updated: Long = 0,
)

@Serializable
data class PlaylistsResponse(val playlists: List<Playlist> = emptyList(), val run: PlaylistRun? = null)

@Serializable
data class ClientPage(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = false,
    val volume: Double = 1.0,
    val status: String = "idle",
    val ready: Boolean = false,
    val underruns: Int = 0,
    val resyncs: Int = 0,
    val lastSeenSeconds: Double = 0.0,
)

@Serializable
data class ClientsResponse(val clients: List<ClientPage> = emptyList(), val leaseSeconds: Int = 90)

@Serializable
data class VoicesResponse(val voices: List<String> = emptyList(), val default: String = "", val configured: Boolean = false)

/** Readable names for pianobar's commands, matching the web player. */
val actionLabels = mapOf(
    "act_help" to "Help & shortcuts", "act_songlove" to "Love song", "act_songban" to "Ban song",
    "act_stationaddmusic" to "Add music", "act_stationcreate" to "Create a station",
    "act_stationdelete" to "Delete station", "act_songexplain" to "Why this song?",
    "act_stationaddbygenre" to "Explore genres", "act_history" to "Song history",
    "act_songinfo" to "Song information", "act_addshared" to "Add shared station",
    "act_songnext" to "Next song", "act_songpausetoggle" to "Play / pause", "act_quit" to "Quit player",
    "act_stationrename" to "Rename station", "act_stationchange" to "Change station",
    "act_songtired" to "Rest this song", "act_upcoming" to "Upcoming songs",
    "act_stationselectquickmix" to "Mix stations", "act_debug" to "Song details",
    "act_bookmark" to "Bookmark", "act_voldown" to "Volume down", "act_volup" to "Volume up",
    "act_managestation" to "Manage station", "act_songpausetoggle2" to "Play / pause (alternate)",
    "act_stationcreatefromsong" to "Station from this song", "act_songplay" to "Resume",
    "act_songpause" to "Pause", "act_volreset" to "Reset volume", "act_settings" to "Account settings",
    "act_offline" to "Offline / reconnect", "act_web" to "Open browser",
)

val stationActionIds = listOf(
    "act_stationcreate", "act_stationaddmusic", "act_stationaddbygenre", "act_managestation", "act_stationrename",
    "act_addshared", "act_stationcreatefromsong", "act_stationselectquickmix", "act_stationdelete",
)

fun PlayerAction.title() = actionLabels[id] ?: label.ifBlank { id.removePrefix("act_") }
