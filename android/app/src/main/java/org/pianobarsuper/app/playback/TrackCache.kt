package org.pianobarsuper.app.playback

import android.util.Log
import okhttp3.Call
import okhttp3.Response
import org.pianobarsuper.app.net.Api
import org.pianobarsuper.app.net.LoginRequired
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Whole songs from the server, kept on the phone: the one playing and the
 * next. [want] names them in the order they are needed; one worker downloads
 * the first that is missing (resuming a partial file with a Range request), so
 * the song that has to play first never shares the connection with the one
 * after it. Every other file is deleted.
 */
class TrackCache(private val dir: File, private val api: Api, private val onReady: (String) -> Unit) {
    private val lock = Object()
    private var wanted: List<String> = emptyList()
    private var keep: Set<String> = emptySet()
    private var active: String? = null
    private var stopped = false
    /** id to (failures, earliest retry in ms of [now]). */
    private val failures = HashMap<String, Pair<Int, Long>>()
    @Volatile private var call: Call? = null
    @Volatile var signInRequired = false
        private set
    private val worker = Thread({ run() }, "pianobar-tracks")

    init {
        dir.mkdirs()
        worker.isDaemon = true
        worker.start()
    }

    fun file(id: String) = File(dir, id)
    fun has(id: String) = valid(id) && file(id).isFile

    /** The songs to have, most urgent first; [extra] are kept (the song still playing) but not fetched. */
    fun want(ids: List<String>, extra: Collection<String> = emptyList()) {
        synchronized(lock) {
            val next = ids.filter { valid(it) }.distinct()
            val kept = (next + extra.filter { valid(it) }).toSet()
            if (next == wanted && kept == keep) return
            wanted = next
            keep = kept
            failures.keys.retainAll(kept)
            // A song nobody needs any more gives way to the one that is needed.
            if (active != null && active !in wanted) call?.cancel()
            lock.notifyAll()
        }
        evict()
    }

    fun stop() {
        synchronized(lock) { stopped = true; wanted = emptyList(); keep = emptySet(); lock.notifyAll() }
        call?.cancel()
        worker.interrupt()
    }

    /** Delete everything; for after [stop]. */
    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    private fun evict() {
        val kept = synchronized(lock) { keep + listOfNotNull(active) }
        dir.listFiles()?.forEach { if (it.name.removeSuffix(PART) !in kept) it.delete() }
    }

    private fun run() {
        while (true) {
            val id: String
            synchronized(lock) {
                while (true) {
                    if (stopped) return
                    val now = now()
                    val candidate = wanted.firstOrNull { !has(it) && (failures[it]?.second ?: 0) <= now }
                    if (candidate != null) { id = candidate; active = candidate; break }
                    val retry = wanted.filter { !has(it) }.mapNotNull { failures[it]?.second }.minOrNull()
                    try { lock.wait(if (retry != null) (retry - now).coerceAtLeast(10) else 0) } catch (e: InterruptedException) { return }
                }
            }
            val result = try { download(id) } catch (e: LoginRequired) {
                signInRequired = true; Result.Failed
            } catch (e: IOException) {
                if (!stopped) Log.w(TAG, "song download interrupted: ${e.message}")
                Result.Failed
            }
            synchronized(lock) {
                active = null
                when (result) {
                    Result.Done -> failures.remove(id)
                    // The server is still fetching it from Pandora.
                    Result.NotYet -> failures[id] = (failures[id]?.first ?: 0) to now() + 1500
                    Result.Failed -> {
                        val count = (failures[id]?.first ?: 0) + 1
                        failures[id] = count to now() + (1000L shl count.coerceAtMost(5)).coerceAtMost(30_000)
                    }
                }
            }
            if (result == Result.Done) onReady(id)
            evict()
        }
    }

    private enum class Result { Done, NotYet, Failed }

    private fun download(id: String): Result {
        val part = File(dir, id + PART)
        val from = if (part.isFile) part.length() else 0L
        val request = api.request("api/tracks/$id").apply { if (from > 0) header("Range", "bytes=$from-") }.build()
        val current = api.client.newCall(request)
        call = current
        synchronized(lock) { if (stopped || id !in wanted) { call = null; return Result.Failed } }
        try {
            current.execute().use { response ->
                when (response.code) {
                    503 -> return Result.NotYet
                    // The partial file is already whole, or no longer matches: start again.
                    416 -> { part.delete(); return Result.Failed }
                    200 -> save(response, part, append = false)
                    206 -> {
                        if (response.header("Content-Range")?.startsWith("bytes $from-") != true) { part.delete(); return Result.Failed }
                        save(response, part, append = true)
                    }
                    else -> { Api.check(response); return Result.Failed }
                }
            }
        } finally { call = null }
        if (!part.renameTo(file(id))) { part.delete(); return Result.Failed }
        return Result.Done
    }

    private fun save(response: Response, part: File, append: Boolean) {
        // A sign-in page instead of a song.
        if (response.header("Content-Type")?.startsWith("audio/") != true) throw LoginRequired()
        val body = response.body ?: throw IOException("Empty response")
        val expected = body.contentLength()
        var size = if (append) part.length() else 0L
        var received = 0L
        FileOutputStream(part, append).use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    received += count
                    if (size > LIMIT) throw IOException("Song too large")
                    out.write(buffer, 0, count)
                }
            }
        }
        if (expected >= 0 && received != expected) throw IOException("Download ended early")
    }

    private fun now() = System.nanoTime() / 1_000_000

    companion object {
        private const val PART = ".part"
        private const val LIMIT = 64L * 1024 * 1024
        fun valid(id: String) = id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
