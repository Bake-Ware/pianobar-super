package org.pianobarsuper.app.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.json.JSONArray
import org.pianobarsuper.app.net.Api
import org.pianobarsuper.app.net.Song
import org.pianobarsuper.app.net.json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A song stored on the phone, playable without the server. */
@Serializable
data class LocalTrack(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val duration: Int = 0,
    /** The server's saved-song id (…​.mka) when it was downloaded from the library. */
    val serverId: String = "",
    val hasCover: Boolean = false,
) {
    val label get() = title.ifBlank { "Untitled song" }
}

sealed interface DownloadState {
    data object Queued : DownloadState
    data class Running(val fraction: Float) : DownloadState
    data class Failed(val message: String) : DownloadState
}

/**
 * Songs kept on the phone: downloaded from the server's library (as tagged
 * M4A/MP3 with cover art), or imported from a file. Files are named by id, so
 * no server-supplied text ever becomes a path.
 */
class PhoneLibrary(context: Context, private val scope: CoroutineScope, private val api: () -> Api?) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "offline").apply { mkdirs() }
    private val index = File(directory, "index.json")
    private val lock = Any()

    private val _tracks = MutableStateFlow(load())
    val tracks: StateFlow<List<LocalTrack>> = _tracks.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    /** Pending or failed downloads by server song id. */
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()
    private val queue = Channel<Song>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            for (song in queue) {
                if (_downloads.value[song.id] !is DownloadState.Queued) continue
                try {
                    download(song)
                    _downloads.update { it - song.id }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _downloads.update { it + (song.id to DownloadState.Failed(e.message ?: "Download failed.")) }
                }
            }
        }
    }

    fun file(id: String): File {
        require(id.matches(Regex("[0-9a-f]{64}"))) { "Invalid track" }
        return File(directory, "$id.audio")
    }

    fun cover(id: String): File? = File(directory, "$id.jpg").takeIf { it.isFile }

    fun byServerId(serverId: String) = _tracks.value.firstOrNull { it.serverId == serverId }

    /** Queue saved songs for download; already-downloaded ones are skipped. */
    fun enqueue(songs: List<Song>) {
        val have = _tracks.value.map { it.serverId }.toSet()
        for (song in songs) {
            if (song.id in have || !song.id.matches(Regex("[0-9a-f]{64}\\.mka"))) continue
            val current = _downloads.value[song.id]
            if (current is DownloadState.Queued || current is DownloadState.Running) continue
            _downloads.update { it + (song.id to DownloadState.Queued) }
            queue.trySend(song)
        }
    }

    fun dismissFailed() = _downloads.update { map -> map.filterValues { it !is DownloadState.Failed } }

    private fun download(song: Song) {
        val api = api() ?: throw IOException("Choose your server first.")
        val id = song.id.removeSuffix(".mka")
        _downloads.update { it + (song.id to DownloadState.Running(0f)) }
        val temporary = File.createTempFile("download-", ".tmp", directory)
        try {
            api.client.newCall(api.request("api/download/${song.id}").build()).execute().use { response ->
                Api.check(response)
                val type = response.header("Content-Type") ?: ""
                if (!type.startsWith("audio/")) throw IOException("The server did not send audio.")
                val body = response.body ?: throw IOException("Empty download.")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var written = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            written += count
                            if (written > 100L * 1024 * 1024) throw IOException("Songs larger than 100 MB are skipped.")
                            output.write(buffer, 0, count)
                            if (total > 0) _downloads.update { it + (song.id to DownloadState.Running(written.toFloat() / total)) }
                        }
                    }
                }
            }
            val (meta, art) = probe(temporary)
            synchronized(lock) {
                if (!temporary.renameTo(file(id))) throw IOException("Could not store the song.")
                art?.let { File(directory, "$id.jpg").writeBytes(it) }
                val track = LocalTrack(id, song.title.ifBlank { meta.title }, song.artist.ifBlank { meta.artist },
                    song.album.ifBlank { meta.album }, if (song.duration > 0) song.duration else meta.duration, song.id, art != null)
                save(_tracks.value.filter { it.id != id } + track)
            }
        } finally { temporary.delete() }
    }

    suspend fun importFile(uri: Uri): LocalTrack = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw IOException("Choose an audio file from the file picker.")
        val temporary = File.createTempFile("import-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            (app.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the selected file.")).use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 100L * 1024 * 1024) throw IOException("Choose songs smaller than 100 MB.")
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            val (meta, art) = probe(temporary)
            var title = meta.title
            if (title.isBlank()) app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) title = it.getString(0) ?: ""
            }
            synchronized(lock) {
                _tracks.value.firstOrNull { it.id == id }?.let { return@withContext it }
                if (!temporary.renameTo(file(id))) throw IOException("Could not store the song.")
                art?.let { File(directory, "$id.jpg").writeBytes(it) }
                val track = LocalTrack(id, title.ifBlank { "Imported song" }, meta.artist, meta.album, meta.duration, hasCover = art != null)
                save(_tracks.value + track)
                track
            }
        } finally { temporary.delete() }
    }

    fun remove(id: String) {
        synchronized(lock) {
            save(_tracks.value.filter { it.id != id })
            file(id).delete()
            File(directory, "$id.jpg").delete()
        }
    }

    private data class Probe(val title: String, val artist: String, val album: String, val duration: Int)

    private fun probe(file: File): Pair<Probe, ByteArray?> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes")
                throw IOException("That file does not contain playable audio.")
            val probe = Probe(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: "",
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "",
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "",
                ((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt(),
            )
            return probe to retriever.embeddedPicture?.takeIf { it.size < 8 * 1024 * 1024 }
        } finally { retriever.release() }
    }

    private fun load(): List<LocalTrack> {
        val loaded = try { json.decodeFromString<List<LocalTrack>>(index.readText()) } catch (e: Exception) { null }
        if (loaded != null) return loaded.filter { file(it.id).isFile }
        // The first native app kept imports in a preference; carry them over once.
        val legacy = mutableListOf<LocalTrack>()
        try {
            val list = JSONArray(app.getSharedPreferences("library", 0).getString("tracks", "[]"))
            for (i in 0 until list.length()) {
                val entry = list.getJSONObject(i)
                val id = entry.getString("id")
                if (id.matches(Regex("[0-9a-f]{64}")) && file(id).isFile)
                    legacy.add(LocalTrack(id, entry.optString("title", "Imported song"), entry.optString("artist", "")))
            }
        } catch (e: Exception) { /* Invalid legacy entries are never used as paths. */ }
        writeIndex(legacy)
        return legacy
    }

    private fun save(tracks: List<LocalTrack>) {
        writeIndex(tracks)
        _tracks.value = tracks
    }

    private fun writeIndex(tracks: List<LocalTrack>) {
        val temporary = File(directory, "index.json.tmp")
        temporary.writeText(json.encodeToString(tracks))
        if (!temporary.renameTo(index)) throw IOException("Could not save the offline library.")
    }
}
