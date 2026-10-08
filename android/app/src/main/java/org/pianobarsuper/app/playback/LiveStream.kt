package org.pianobarsuper.app.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

const val TAG = "pianobar"

enum class StreamStatus { Connecting, Waiting, Playing, Reconnecting, SignInRequired, Stopped }

/**
 * The phone as one of the server's listening devices: it registers like a
 * browser, keeps its lease with heartbeats, and plays the server's live PCM
 * through an AudioTrack. Runs on its own threads; never blocks the UI.
 */
class LiveStream(private val api: Api, private val deviceName: String, private val onStatus: (StreamStatus, String) -> Unit) {
    @Volatile var duck = 1f
    /** Muted for a phone call or another app's sound; the lease is kept. */
    @Volatile var held = false
    @Volatile private var cancelled = false
    @Volatile private var call: Call? = null
    @Volatile var clientId = ""
        private set
    @Volatile private var serverVolume = 1f
    @Volatile private var enabled = true
    @Volatile private var status = StreamStatus.Connecting
    @Volatile private var underruns = 0
    private val heartbeats = Executors.newSingleThreadScheduledExecutor()
    private var beat: ScheduledFuture<*>? = null
    private var thread: Thread? = null

    fun start() {
        thread = Thread({ run() }, "pianobar-pcm").also { it.start() }
        beat = heartbeats.scheduleWithFixedDelay({ heartbeat() }, 2, 10, TimeUnit.SECONDS)
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
        val playing = status == StreamStatus.Playing && !held
        val page = post("api/clients/heartbeat", buildJsonObject {
            put("id", id)
            put("status", if (playing) "playing" else if (held) "idle" else "waiting")
            put("ready", !held)
            put("underruns", underruns)
        }) ?: return
        page["volume"]?.jsonPrimitive?.doubleOrNull?.let { serverVolume = it.toFloat() }
        page["enabled"]?.jsonPrimitive?.booleanOrNull?.let { enabled = it }
    }

    private fun run() {
        var retry = 1
        while (!cancelled) {
            var output: AudioTrack? = null
            var registered = ""
            try {
                set(if (retry == 1) StreamStatus.Connecting else StreamStatus.Reconnecting, "")
                registered = post("api/clients/register", buildJsonObject { put("name", deviceName) })
                    ?.get("id")?.jsonPrimitive?.content ?: throw IOException("Could not register this phone.")
                clientId = registered
                post("api/clients/update", buildJsonObject { put("id", registered); put("enabled", true) })
                // Keep the server's own speakers going when the phone joins.
                val state = try { api.client.newCall(api.request("api/state").build()).execute().use {
                    Api.check(it); json.parseToJsonElement(it.body?.string() ?: "{}").jsonObject["state"]?.jsonObject
                } } catch (e: LoginRequired) { throw e } catch (e: Exception) { null }
                if (state?.get("output")?.jsonPrimitive?.content == "host")
                    post("api/command", buildJsonObject { put("output", "both") })
                val request = api.request("api/audio").header("X-Pianobar-Client", registered).build()
                val current = api.streamClient.newCall(request)
                call = current
                if (cancelled) break
                current.execute().use { response ->
                    Api.check(response)
                    if (response.header("Content-Type") != "application/octet-stream") throw LoginRequired()
                    set(StreamStatus.Waiting, "")
                    DataInputStream(BufferedInputStream(response.body!!.byteStream(), 65536)).use { input ->
                        var rate = 0
                        var channels = 0
                        var epoch = -1
                        var wasMuted = false
                        // Jitter buffer: audio arrives in bursts (proxies, Wi-Fi), so hold a cushion
                        // before playing and refill it after an underrun, growing it each time.
                        var cushion = 1.5f
                        var primed = false
                        var written = 0L
                        var lastUnderruns = 0
                        while (!cancelled) {
                            val frame = PcmFrame.read(input)
                            retry = 1
                            val muted = held || !enabled
                            if (muted && !wasMuted) output?.run { pause(); flush(); written = playbackHeadPosition.toLong() and 0xffffffffL; primed = false }
                            wasMuted = muted
                            if (frame.samples.isEmpty() || muted) continue
                            if (output == null || rate != frame.rate || channels != frame.channels) {
                                output?.release()
                                rate = frame.rate
                                channels = frame.channels
                                output = track(rate, channels)
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
                            }
                            track.setVolume(serverVolume * duck)
                            val count = track.underrunCount
                            if (count > lastUnderruns) {
                                // Ran dry: pause (keeping what is queued) and refill a bigger cushion.
                                lastUnderruns = count
                                underruns++
                                cushion = (cushion + .5f).coerceAtMost(4f)
                                if (primed) { track.pause(); primed = false }
                                Log.i(TAG, "underrun; refilling a ${cushion}s cushion")
                            }
                            var offset = 0
                            while (offset < frame.samples.size && !cancelled && !held && enabled) {
                                val count2 = track.write(frame.samples, offset, frame.samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                                if (count2 < 0) throw IOException("Audio output unavailable")
                                offset += count2
                                written += count2 / channels
                                if (!primed) {
                                    val buffered = written - (track.playbackHeadPosition.toLong() and 0xffffffffL)
                                    if (buffered >= (cushion * rate).toLong() || count2 == 0) {
                                        track.play(); primed = true
                                        if (status != StreamStatus.Playing) set(StreamStatus.Playing, "")
                                    }
                                }
                                if (count2 == 0) Thread.sleep(20)
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
                output?.release()
                call = null
                clientId = ""
                if (registered.isNotEmpty() && !cancelled) post("api/clients/unregister", buildJsonObject { put("id", registered) })
            }
            if (cancelled) break
            try { Thread.sleep(retry * 1000L) } catch (e: InterruptedException) { break }
            retry = (retry * 2).coerceAtMost(15)
        }
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
    }
}
