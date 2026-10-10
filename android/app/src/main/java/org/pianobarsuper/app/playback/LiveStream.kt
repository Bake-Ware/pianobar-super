package org.pianobarsuper.app.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.RequestBody.Companion.toRequestBody
import org.pianobarsuper.app.net.Api
import org.pianobarsuper.app.net.LoginRequired
import org.pianobarsuper.app.net.PcmFrame
import org.pianobarsuper.app.net.json
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

const val TAG = "pianobar"

enum class StreamStatus { Connecting, Waiting, Playing, Reconnecting, SignInRequired, Stopped }

/**
 * The phone as one of the server's listening devices: it registers like a
 * browser, keeps its lease with heartbeats, and plays the server's live PCM
 * through an AudioTrack. Runs on its own threads; never blocks the UI.
 *
 * [park] silences it at once and asks the server to stop sending audio, but
 * keeps the registration and connection so [resume] can start again quickly.
 */
class LiveStream(val api: Api, private val deviceName: String, private val onStatus: (StreamStatus, String) -> Unit) {
    @Volatile var duck = 1f
    /** Muted for a phone call or another app's sound; the lease is kept. */
    @Volatile var held = false
    @Volatile private var cancelled = false
    @Volatile private var call: Call? = null
    @Volatile var clientId = ""
        private set
    @Volatile private var serverVolume = 1f
    @Volatile private var enabled = true
    /** Bumped when this phone changes [enabled] itself, so an older heartbeat reply can't undo it. */
    @Volatile private var enabledChange = 0
    @Volatile private var parked = false
    @Volatile private var output: AudioTrack? = null
    private val playLock = Any()
    @Volatile private var status = StreamStatus.Connecting
    @Volatile private var underruns = 0
    private val heartbeats = Executors.newSingleThreadScheduledExecutor()
    private var beat: ScheduledFuture<*>? = null
    private var thread: Thread? = null

    fun start() {
        thread = Thread({ run() }, "pianobar-pcm").also { it.start() }
        beat = heartbeats.scheduleWithFixedDelay({ heartbeat() }, 2, 10, TimeUnit.SECONDS)
    }

    /** Silence now and stop the server sending audio; keep the lease and connection. */
    fun park() {
        synchronized(playLock) { parked = true; output?.pause() }
        // Report Playing again once audio flows after resume().
        if (status == StreamStatus.Playing) set(StreamStatus.Waiting, "")
        enabledChange++
        background { sendEnabled() }
    }

    /** Undo [park]. False if this stream has ended and a new one is needed. */
    fun resume(): Boolean {
        if (cancelled) return false
        synchronized(playLock) { parked = false }
        // Assume the server agrees, so the first (prefilled) audio isn't dropped.
        enabledChange++
        enabled = true
        background { sendEnabled(); keepServerSpeakers() }
        return true
    }

    private fun background(work: () -> Unit) = try { heartbeats.execute(work) } catch (e: RejectedExecutionException) { }

    private fun sendEnabled() {
        val id = clientId
        if (cancelled || id.isEmpty()) return
        val change = enabledChange
        val page = post("api/clients/update", buildJsonObject { put("id", id); put("enabled", !parked) }) ?: return
        if (change == enabledChange) page["enabled"]?.jsonPrimitive?.booleanOrNull?.let { enabled = it }
    }

    /** Keep the server's own speakers going when the phone joins. */
    private fun keepServerSpeakers() {
        if (cancelled || parked) return
        val state = try { api.client.newCall(api.request("api/state").build()).execute().use {
            Api.check(it); json.parseToJsonElement(it.body?.string() ?: "{}").jsonObject["state"]?.jsonObject
        } } catch (e: Exception) { null }
        if (state?.get("output")?.jsonPrimitive?.content == "host")
            post("api/command", buildJsonObject { put("output", "both") })
    }

    fun stop() {
        cancelled = true
        call?.cancel()
        thread?.interrupt()
        beat?.cancel(false)
        val id = clientId
        heartbeats.execute { if (id.isNotEmpty()) post("api/clients/unregister", buildJsonObject { put("id", id) }) }
        heartbeats.shutdown()
        set(StreamStatus.Stopped, "")
    }

    private fun set(value: StreamStatus, message: String) {
        if (value != status) Log.i(TAG, "stream $value $message")
        status = value
        onStatus(value, message)
    }

    private fun post(path: String, body: JsonObject): JsonObject? = try {
        api.client.newCall(api.request(path).post(body.toString().toRequestBody(Api.JSON)).build()).execute().use { response ->
            Api.check(response)
            json.parseToJsonElement(response.body?.string() ?: "{}").jsonObject
        }
    } catch (e: LoginRequired) {
        if (!cancelled) { set(StreamStatus.SignInRequired, "Sign in to your server again."); cancelled = true; call?.cancel() }
        null
    } catch (e: Exception) { null }

    private fun heartbeat() {
        val id = clientId
        if (cancelled || id.isEmpty()) return
        val idle = held || parked
        val playing = status == StreamStatus.Playing && !idle
        val change = enabledChange
        val page = post("api/clients/heartbeat", buildJsonObject {
            put("id", id)
            put("status", if (playing) "playing" else if (idle) "idle" else "waiting")
            put("ready", !idle)
            put("underruns", underruns)
        }) ?: return
        page["volume"]?.jsonPrimitive?.doubleOrNull?.let { serverVolume = it.toFloat() }
        if (change == enabledChange) page["enabled"]?.jsonPrimitive?.booleanOrNull?.let { enabled = it }
    }

    private fun run() {
        var retry = 1
        while (!cancelled) {
            var registered = ""
            try {
                set(if (retry == 1) StreamStatus.Connecting else StreamStatus.Reconnecting, "")
                registered = post("api/clients/register", buildJsonObject { put("name", deviceName) })
                    ?.get("id")?.jsonPrimitive?.content ?: throw IOException("Could not register this phone.")
                clientId = registered
                ListenTimer.mark("registered")
                // Enable and check the server's output while the audio request opens: the server
                // sends recent audio (the prefill) as soon as this phone is enabled.
                background { sendEnabled(); keepServerSpeakers() }
                val request = api.request("api/audio").header("X-Pianobar-Client", registered)
                    .header("X-Pianobar-Prefill", PREFILL_SECONDS.toString()).header("X-Pianobar-Marks", "voice").build()
                val current = api.streamClient.newCall(request)
                call = current
                if (cancelled) break
                current.execute().use { response ->
                    Api.check(response)
                    if (response.header("Content-Type") != "application/octet-stream") throw LoginRequired()
                    set(StreamStatus.Waiting, "")
                    ListenTimer.mark("audio opened")
                    DataInputStream(BufferedInputStream(response.body!!.byteStream(), 65536)).use { input ->
                        var rate = 0
                        var channels = 0
                        var epoch = -1
                        var wasMuted = false
                        // Jitter buffer: audio arrives in bursts (proxies, Wi-Fi). Play the moment audio
                        // arrives (the server's prefill usually builds a buffer at once), then refill
                        // to [target] where a pause is least noticed: just before the DJ talks, or
                        // after running dry. Each underrun grows the target.
                        var target = 1.5f
                        var cushion = 0f
                        var breaks = 0
                        var primed = false
                        var written = 0L
                        var lastUnderruns = 0
                        // Listen-start timing: the first audio after connecting or unmuting.
                        var starting = true
                        var startHead = -1L
                        while (!cancelled) {
                            val frame = PcmFrame.read(input)
                            retry = 1
                            val muted = held || !enabled || parked
                            if (muted && !wasMuted) output?.run { pause(); flush(); written = playbackHeadPosition.toLong() and 0xffffffffL; primed = false }
                            if (muted) { starting = true; startHead = -1; cushion = 0f }
                            wasMuted = muted
                            val track0 = output
                            if (frame.voiceMark && !muted && track0 != null) {
                                // The DJ starts with the next frame: hold here, before the line, until
                                // the buffer is back to the target. The DJ plays a little behind live.
                                breaks++
                                val buffered = written - (track0.playbackHeadPosition.toLong() and 0xffffffffL)
                                // Not worth a pause for a few hundred milliseconds.
                                if (primed && buffered < ((target - .25f) * rate).toLong()) { track0.pause(); primed = false; cushion = target }
                                Log.i(TAG, "DJ break $breaks: buffer ${buffered * 1000 / maxOf(rate, 1)} ms, target ${(target * 1000).toInt()} ms")
                            }
                            if (frame.samples.isEmpty() || muted) continue
                            if (starting && startHead < 0 && !primed) { ListenTimer.mark("first audio frame"); startHead = 0 }
                            if (output == null || rate != frame.rate || channels != frame.channels) {
                                rate = frame.rate
                                channels = frame.channels
                                synchronized(playLock) { output?.release(); output = track(rate, channels) }
                                epoch = frame.epoch
                                written = 0
                                primed = false
                                lastUnderruns = 0
                            }
                            val track = output!!
                            // A new epoch means the server cut the song short: drop what is queued.
                            if (epoch != frame.epoch) {
                                track.pause(); track.flush(); epoch = frame.epoch
                                written = track.playbackHeadPosition.toLong() and 0xffffffffL
                                primed = false
                                cushion = 0f
                            }
                            track.setVolume(serverVolume * duck)
                            val count = track.underrunCount
                            if (count > lastUnderruns) {
                                // Ran dry: pause (keeping what is queued), refill, and aim higher next time.
                                lastUnderruns = count
                                underruns++
                                cushion = target
                                target = (target + .5f).coerceAtMost(4f)
                                if (primed) { track.pause(); primed = false }
                                Log.i(TAG, "underrun $underruns after $breaks DJ breaks; refilling a ${cushion}s cushion")
                            }
                            var offset = 0
                            while (offset < frame.samples.size && !cancelled && !held && enabled && !parked) {
                                val count2 = track.write(frame.samples, offset, frame.samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                                if (count2 < 0) throw IOException("Audio output unavailable")
                                offset += count2
                                written += count2 / channels
                                if (!primed) {
                                    val buffered = written - (track.playbackHeadPosition.toLong() and 0xffffffffL)
                                    if (buffered >= (cushion * rate).toLong() || count2 == 0) {
                                        // park() pauses under the same lock, so it can't be undone here.
                                        synchronized(playLock) { if (!parked) { track.play(); primed = true } }
                                        if (!primed) break
                                        if (starting) { ListenTimer.mark("track playing"); startHead = track.playbackHeadPosition.toLong() and 0xffffffffL }
                                        if (status != StreamStatus.Playing) set(StreamStatus.Playing, "")
                                    }
                                }
                                if (count2 == 0) Thread.sleep(20)
                            }
                            if (starting && primed && (track.playbackHeadPosition.toLong() and 0xffffffffL) != startHead) {
                                starting = false
                                ListenTimer.finish("audible")
                            }
                        }
                    }
                }
            } catch (e: LoginRequired) {
                if (!cancelled) set(StreamStatus.SignInRequired, "Sign in to your server again.")
                break
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!cancelled) { Log.w(TAG, "stream interrupted", e); set(StreamStatus.Reconnecting, "Connection interrupted. Reconnecting…") }
            } finally {
                synchronized(playLock) { output?.release(); output = null }
                call = null
                clientId = ""
                if (registered.isNotEmpty() && !cancelled) post("api/clients/unregister", buildJsonObject { put("id", registered) })
            }
            if (cancelled) break
            try { Thread.sleep(retry * 1000L) } catch (e: InterruptedException) { break }
            retry = (retry * 2).coerceAtMost(15)
        }
    }

    private companion object {
        /** Seconds of recent audio to ask the server for, so playback can start at once. */
        const val PREFILL_SECONDS = 2
    }

    private fun track(rate: Int, channels: Int): AudioTrack {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw IOException("Unsupported audio format")
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            // Room for the largest cushion (4 s) plus a burst on top.
            .setBufferSizeInBytes(maxOf(minimum, rate * channels * 2 * 6))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                // By default a streaming track doesn't start until its whole buffer is full (about
                // 4.5 s of silence); start as soon as there is anything to play.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) it.setStartThresholdInFrames(minimum / (channels * 2))
            }
    }
}
