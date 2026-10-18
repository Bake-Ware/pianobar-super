package org.pianobarsuper.app.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.RequestBody.Companion.toRequestBody
import org.pianobarsuper.app.net.Api
import org.pianobarsuper.app.net.ApiError
import org.pianobarsuper.app.net.DjVoice
import org.pianobarsuper.app.net.LoginRequired
import org.pianobarsuper.app.net.Snapshot
import org.pianobarsuper.app.net.json
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.pow

/** One way of hearing the server's radio on this phone. */
interface Listening {
    val api: Api
    /** Lowered while another app talks over this one. */
    var duck: Float
    /** Muted for a phone call or another app's sound; the lease is kept. */
    var held: Boolean
    fun start()
    /** Silence now; keep the registration so [resume] is quick. */
    fun park()
    /** Undo [park]. False if this one has ended and a new one is needed. */
    fun resume(): Boolean
    fun stop()
}

/**
 * The phone as one of the server's listening devices, playing whole songs
 * instead of the live stream. The server downloads each song from Pandora as
 * soon as it is known; the phone fetches the current song (only that one
 * before playing) and then the next while the first plays, so songs follow
 * each other without a gap and Next jumps straight to the file already here.
 * A dropped connection costs nothing while a song plays.
 *
 * The server's player stays in charge: the phone follows its song, pauses and
 * skips (from any device), and keeps close to its position by nudging the
 * playback speed. DJ lines are fetched as files too and mixed here. Like
 * [LiveStream] it registers as a device and keeps its lease with heartbeats.
 *
 * Players live on the main thread; downloads and server calls have their own.
 */
@UnstableApi
class TrackStream(
    context: Context,
    override val api: Api,
    private val deviceName: String,
    private val snapshots: StateFlow<Snapshot?>,
    private val skips: Flow<Unit>,
    private val onStatus: (StreamStatus, String) -> Unit,
    /** The server only streams live audio (an older version). */
    private val onLegacy: () -> Unit,
) : Listening {
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val calls = Executors.newSingleThreadScheduledExecutor()
    private val cache = TrackCache(File(context.cacheDir, "radio-tracks"), api) { main.post { reconcile() } }
    private val lines = File(context.cacheDir, "radio-lines").apply { mkdirs() }
    private val music: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        // Keep Wi-Fi up for the next song's download while the screen is off.
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
    private val voice: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), false)
        .build()

    override var duck = 1f
        set(value) { field = value; main.post { applyVolume() } }
    override var held = false
        set(value) { val back = field && !value; field = value; main.post { if (back) sync = true; reconcile() } }

    @Volatile private var cancelled = false
    @Volatile private var parked = false
    @Volatile private var enabled = true
    /** Bumped when this phone changes [enabled] itself, so an older heartbeat reply can't undo it. */
    @Volatile private var enabledChange = 0
    @Volatile private var serverVolume = 1f
    @Volatile private var playingNow = false
    @Volatile var clientId = ""
        private set
    @Volatile private var waits = 0
    @Volatile private var resyncs = 0

    // Main thread only.
    private var snapshot: Snapshot? = null
    /** When the server's elapsed time last changed, for estimating its position between updates. */
    private var stamp = 0L
    /** Join the server's position at the next chance (starting, or back from a pause or a call). */
    private var sync = true
    /** Corrections left after loading a song mid-way: starting (or seeking) takes a moment, which they make up. */
    private var settle = 0
    private var status = StreamStatus.Connecting
    private var heard = false
    private var reconciling = false
    /** Songs whose files wouldn't play even after downloading them again. */
    private val broken = mutableSetOf<String>()
    private val retried = mutableSetOf<String>()
    private var lineSeen = 0
    private var linePlaying = 0
    /** The music waits while the DJ talks (instead of dipping under the line). */
    private var lineHolds = false
    private var lineDuck = 1f
    private var lineLevel = 1f
    /** The music's level under a DJ line, ramped toward [lineDuck]. */
    private var gain = 1f
    private val ramp = object : Runnable {
        override fun run() {
            gain = if (abs(lineDuck - gain) < .03f) lineDuck else gain + (lineDuck - gain) * .35f
            applyVolume()
            if (gain != lineDuck) main.postDelayed(this, 30)
        }
    }

    override fun start() {
        music.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { if (!cancelled) main.post { reconcile() } }
            override fun onPlayerError(error: PlaybackException) { unplayable(error) }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val why = mapOf(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO to "gapless", Player.MEDIA_ITEM_TRANSITION_REASON_SEEK to "jump",
                    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED to "load")[reason] ?: "repeat"
                if (item != null) Log.i(TAG, "song …${item.mediaId.takeLast(8)} ($why)")
            }
        })
        voice.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) endLine() }
            override fun onPlayerError(error: PlaybackException) { Log.w(TAG, "could not play a DJ line", error); endLine() }
        })
        scope.launch { snapshots.collect { onSnapshot(it) } }
        scope.launch { skips.collect { skip() } }
        background { register() }
        try { calls.scheduleWithFixedDelay({ heartbeat() }, 10, 10, TimeUnit.SECONDS) } catch (e: RejectedExecutionException) { }
        set(StreamStatus.Connecting, "")
    }

    override fun park() {
        parked = true
        enabledChange++
        background { sendEnabled() }
        main.post { endLine(); reconcile() }
    }

    override fun resume(): Boolean {
        if (cancelled) return false
        parked = false
        // Assume the server agrees, so playback starts at once.
        enabledChange++
        enabled = true
        background { sendEnabled() }
        main.post { sync = true; heard = false; reconcile() }
        return true
    }

    override fun stop() {
        if (cancelled) return
        cancelled = true
        scope.cancel()
        main.removeCallbacks(ramp)
        cache.stop()
        music.release()
        voice.release()
        val id = clientId
        background {
            if (id.isNotEmpty()) post("api/clients/unregister", buildJsonObject { put("id", id) })
            cache.clear()
            lines.listFiles()?.forEach { it.delete() }
        }
        calls.shutdown()
        set(StreamStatus.Stopped, "")
    }

    // ---- Following the server -------------------------------------------------------------

    private fun onSnapshot(snap: Snapshot?) {
        if (cancelled || snap == null) return
        if (snap.delivery != "files") { onLegacy(); return }
        val old = snapshot?.state
        if (old == null || old.elapsed != snap.state.elapsed || old.trackId != snap.state.trackId || old.paused != snap.state.paused)
            stamp = SystemClock.elapsedRealtime()
        snapshot = snap
        line(snap)
        reconcile()
    }

    /** Where the server's player is in its song, in ms. */
    private fun serverPosition(): Long {
        val state = snapshot?.state ?: return 0
        val base = state.elapsed * 1000L
        return if (state.paused) base else base + (SystemClock.elapsedRealtime() - stamp)
    }

    /** Make the phone's player match the server's state. Safe to call any time. */
    private fun reconcile() {
        if (cancelled || reconciling) return
        val snap = snapshot ?: return
        reconciling = true
        try {
            val state = snap.state
            val current = state.trackId
            val next = state.nextTrackId
            val silent = parked || held || !enabled
            if (music.currentMediaItemIndex > 0) music.removeMediaItems(0, music.currentMediaItemIndex)  // Finished songs.
            val playing = music.currentMediaItem?.mediaId
            // The song needed now first, then the next; nothing while parked.
            cache.want(if (parked) emptyList() else listOf(current, next), queued())
            val moved = !silent && current.isNotEmpty() && follow(current, next, playing)
            queueNext(current, next)
            // Measure only once a seek or load has taken effect.
            if (!moved) nudge(current)
            music.playWhenReady = !silent && !state.paused && !lineHolds
            applyVolume()
            publish()
        } finally { reconciling = false }
    }

    /** Follow the server's song; true if this moved the player. */
    private fun follow(current: String, next: String, playing: String?): Boolean {
        val ended = music.playbackState == Player.STATE_ENDED
        var moved = true
        when {
            // Also when this phone finished the song and the server plays it again.
            playing == current -> if (sync || (ended && serverPosition() + 5000 < music.duration)) { here(serverPosition()); sync = false; settle = 2 } else moved = false
            // A little ahead: the server is finishing the song this phone already finished (or skipped).
            !sync && playing != null && playing == next && !ended && music.currentPosition < AHEAD_MS -> moved = false
            else -> {
                val index = queued().indexOf(current)
                if (index > music.currentMediaItemIndex && !sync) {
                    // The server moved on first: a skip from elsewhere, or this phone running behind.
                    val left = music.duration - music.currentPosition
                    if (ended || !music.isPlaying || music.duration == C.TIME_UNSET || left > 2500) { val at = startAt(serverPosition()); seek(index, at); settle = if (at > 0) 2 else 0 } else moved = false
                } else if (cache.has(current) && current !in broken) {
                    if (playing == null) ListenTimer.mark("first song downloaded")
                    val at = startAt(serverPosition())
                    music.setMediaItem(item(current), at)
                    music.prepare()
                    sync = false
                    settle = if (at > 0) 2 else 0
                } else if (music.mediaItemCount > 0) {
                    // Not here yet: silence the old song; the new one starts when it arrives.
                    waits++
                    Log.i(TAG, "waiting for the next song's file")
                    music.stop()
                    music.clearMediaItems()
                } else moved = false
            }
        }
        return moved
    }

    /** Keep the next song right behind the current one, so the change is gapless. */
    private fun queueNext(current: String, next: String) {
        if (music.mediaItemCount == 0 || music.currentMediaItem?.mediaId != current) return
        val after = music.currentMediaItemIndex + 1
        val queued = if (music.mediaItemCount > after) music.getMediaItemAt(after).mediaId else ""
        val wanted = if (next.isNotEmpty() && next != current && next !in broken && cache.has(next)) next else ""
        if (queued == wanted) return
        if (music.mediaItemCount > after) music.removeMediaItems(after, music.mediaItemCount)
        if (wanted.isNotEmpty()) {
            music.addMediaItem(item(wanted))
            // Added after the last song ended: carry on with it.
            if (music.playbackState == Player.STATE_ENDED) music.seekTo(after, 0)
        }
    }

    /**
     * Stay close to the server: the phone changes songs without a gap but the server
     * takes a moment to open each one, so the phone drifts ahead. A 2% change in speed
     * (pitch kept) closes a few seconds per song unnoticed; far off, it seeks.
     */
    private fun nudge(current: String) {
        val state = snapshot?.state ?: return
        val inStep = music.currentMediaItem?.mediaId == current && music.isPlaying && !state.paused && linePlaying == 0
        val offset = if (inStep) music.currentPosition - serverPosition() else 0
        val speed = music.playbackParameters.speed
        when {
            !inStep -> if (speed != 1f) music.setPlaybackSpeed(1f)
            settle > 0 -> {
                settle--
                // Behind by about the time the last start took: aim that far ahead.
                if (abs(offset) > SETTLE_MS) here(serverPosition() + (-offset).coerceIn(0, 3000)) else settle = 0
            }
            abs(offset) > RESYNC_MS -> { resyncs++; Log.i(TAG, "resync: ${offset}ms off"); here(serverPosition()); music.setPlaybackSpeed(1f) }
            offset > DRIFT_MS -> if (speed != SLOWER) { Log.i(TAG, "${offset}ms ahead: slowing"); music.setPlaybackSpeed(SLOWER) }
            offset < -DRIFT_MS -> if (speed != FASTER) { Log.i(TAG, "${-offset}ms behind: speeding up"); music.setPlaybackSpeed(FASTER) }
            abs(offset) < IN_STEP_MS -> if (speed != 1f) { Log.i(TAG, "in step again"); music.setPlaybackSpeed(1f) }
        }
    }

    private fun seek(index: Int, position: Long) = music.seekTo(index, position.coerceAtLeast(0))

    /** Seek within the song playing now. */
    private fun here(position: Long) = seek(music.currentMediaItemIndex, position)

    /** Near the top of a song, start it from the top. */
    private fun startAt(position: Long) = if (position < 3000) 0 else position

    private fun queued() = (0 until music.mediaItemCount).map { music.getMediaItemAt(it).mediaId }

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri(Uri.fromFile(cache.file(id))).build()

    /** Next on this phone: play the next file now; the server is told separately. */
    private fun skip() {
        if (cancelled || parked) return
        endLine()
        val after = music.currentMediaItemIndex + 1
        if (music.mediaItemCount > after) {
            music.seekTo(after, 0)
            sync = false
            Log.i(TAG, "next: playing the downloaded song")
        }
    }

    private fun unplayable(error: PlaybackException) {
        val bad = music.currentMediaItem?.mediaId ?: return
        Log.w(TAG, "could not play a song file", error)
        // Fetch it again once; if that fails too, wait for the server's next song.
        if (!retried.add(bad)) broken.add(bad)
        cache.file(bad).delete()
        music.stop()
        music.clearMediaItems()
        main.post { reconcile() }
    }

    private fun applyVolume() {
        if (cancelled) return
        val base = (serverVolume * duck).coerceIn(0f, 1f)
        music.volume = base * gain
        voice.volume = base * lineLevel
    }

    private fun publish() {
        if (cache.signInRequired) { signIn(); return }
        val now = if (music.isPlaying || voice.isPlaying) StreamStatus.Playing else StreamStatus.Waiting
        playingNow = now == StreamStatus.Playing
        if (playingNow && !heard) { heard = true; ListenTimer.finish("audible") }
        set(now, "")
    }

    private fun set(value: StreamStatus, message: String) {
        if (value != status) Log.i(TAG, "tracks $value $message")
        status = value
        onStatus(value, message)
    }

    private fun signIn() {
        if (status == StreamStatus.SignInRequired || cancelled) return
        set(StreamStatus.SignInRequired, "Sign in to your server again.")
    }

    // ---- DJ lines ---------------------------------------------------------------------------

    private fun line(snap: Snapshot) {
        val line = snap.djVoice ?: return
        // The server stopped it (a skip or a station change).
        if (linePlaying != 0 && line.id == linePlaying && line.status in setOf("idle", "error")) endLine()
        if (line.status != "on_air" || line.id <= lineSeen || line.audio.isEmpty()) return
        lineSeen = line.id
        if (parked || held || !enabled) return
        if (line.songKey.isNotEmpty() && line.songKey != snap.state.songKey) return
        val id = line.id
        background {
            val file = File(lines, "line-$id")
            val ok = fetchLine(line, file)
            main.post {
                val latest = snapshot?.djVoice
                if (ok && !cancelled && !parked && latest?.id == id && latest.status in setOf("on_air", "done")) playLine(id, file, line)
                lines.listFiles()?.forEach { if (it != file) it.delete() }
            }
        }
    }

    private fun fetchLine(line: DjVoice, file: File): Boolean = try {
        api.client.newCall(api.request(line.audio.removePrefix("/")).build()).execute().use { response ->
            Api.check(response)
            if (response.header("Content-Type")?.startsWith("audio/") != true) throw LoginRequired()
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (bytes.isEmpty() || bytes.size > 16 * 1024 * 1024) throw IOException("Bad DJ line")
            file.writeBytes(bytes)
            true
        }
    } catch (e: Exception) { Log.w(TAG, "could not fetch a DJ line: ${e.message}"); false }

    private fun playLine(id: Int, file: File, line: DjVoice) {
        Log.i(TAG, "DJ line $id " + if (line.overMusic) "over the music" else "as a break")
        linePlaying = id
        lineHolds = !line.overMusic
        lineDuck = if (line.overMusic) decibels(line.duck) else 1f
        lineLevel = decibels(line.level)
        voice.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        voice.prepare()
        voice.play()
        main.removeCallbacks(ramp)
        main.post(ramp)
        reconcile()
    }

    private fun endLine() {
        if (cancelled || linePlaying == 0) return
        Log.i(TAG, "DJ line $linePlaying ended")
        linePlaying = 0
        lineHolds = false
        lineDuck = 1f
        voice.stop()
        voice.clearMediaItems()
        main.removeCallbacks(ramp)
        main.post(ramp)
        reconcile()
    }

    private fun decibels(value: Double) = 10.0.pow(value / 20).toFloat().coerceIn(0f, 1f)

    // ---- The device lease ------------------------------------------------------------------

    private fun background(work: () -> Unit) = try { calls.execute(work) } catch (e: RejectedExecutionException) { }

    private fun call(path: String, body: JsonObject): JsonObject =
        api.client.newCall(api.request(path).post(body.toString().toRequestBody(Api.JSON)).build()).execute().use { response ->
            Api.check(response)
            json.parseToJsonElement(response.body?.string() ?: "{}").jsonObject
        }

    private fun post(path: String, body: JsonObject): JsonObject? = try { call(path, body) } catch (e: LoginRequired) {
        main.post { signIn() }; null
    } catch (e: Exception) { null }

    private fun register(retry: Long = 1) {
        if (cancelled || clientId.isNotEmpty()) return
        val id = post("api/clients/register", buildJsonObject { put("name", deviceName) })?.get("id")?.jsonPrimitive?.content
        if (id == null) {
            try { calls.schedule({ register((retry * 2).coerceAtMost(15)) }, retry, TimeUnit.SECONDS) } catch (e: RejectedExecutionException) { }
            return
        }
        // Stopped while registering: don't leave the device behind on the server.
        if (cancelled) { post("api/clients/unregister", buildJsonObject { put("id", id) }); return }
        clientId = id
        ListenTimer.mark("registered")
        sendEnabled()
    }

    private fun sendEnabled() {
        val id = clientId
        if (cancelled || id.isEmpty()) return
        val change = enabledChange
        val page = post("api/clients/update", buildJsonObject { put("id", id); put("enabled", !parked) }) ?: return
        if (change == enabledChange) page["enabled"]?.jsonPrimitive?.booleanOrNull?.let { setEnabled(it) }
    }

    private fun setEnabled(value: Boolean) {
        if (value == enabled) return
        enabled = value
        main.post { if (value) sync = true; reconcile() }
    }

    private fun heartbeat() {
        val id = clientId
        if (cancelled || id.isEmpty()) return
        val idle = held || parked
        val change = enabledChange
        val page = try {
            call("api/clients/heartbeat", buildJsonObject {
                put("id", id)
                put("status", if (playingNow && !idle) "playing" else if (idle) "idle" else "waiting")
                put("ready", !idle)
                put("underruns", waits)
                put("resyncs", resyncs)
            })
        } catch (e: LoginRequired) { main.post { signIn() }; return
        } catch (e: ApiError) {
            // The lease ran out (the phone slept): register again.
            if (e.status == 400) { clientId = ""; register() }
            return
        } catch (e: Exception) { return }
        page["volume"]?.jsonPrimitive?.doubleOrNull?.let { serverVolume = it.toFloat(); main.post { applyVolume() } }
        if (change == enabledChange) page["enabled"]?.jsonPrimitive?.booleanOrNull?.let { setEnabled(it) }
    }

    private companion object {
        const val SETTLE_MS = 600L
        const val DRIFT_MS = 1500L
        const val IN_STEP_MS = 400L
        const val RESYNC_MS = 12_000L
        /** How far into the next song this phone can be while the server finishes the last one. */
        const val AHEAD_MS = 20_000L
        const val SLOWER = .98f
        const val FASTER = 1.02f
    }
}
