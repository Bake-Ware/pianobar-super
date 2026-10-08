package org.pianobarsuper.app.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes as PlatformAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pianobarsuper.app.PianobarApp
import org.pianobarsuper.app.R
import org.pianobarsuper.app.ui.MainActivity

/** What this phone is playing: the server's live stream, downloaded songs, or nothing. */
data class PhonePlayback(
    val listening: Boolean = false,
    val stream: StreamStatus = StreamStatus.Stopped,
    val message: String = "",
    val local: Boolean = false,
    val localTrack: String = "",
    val localPlaying: Boolean = false,
    val localIndex: Int = 0,
    val localQueue: List<String> = emptyList(),
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/** Entry points for the screens. Everything runs in the one playback service. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object Playback {
    private val _state = MutableStateFlow(PhonePlayback())
    val state: StateFlow<PhonePlayback> = _state.asStateFlow()
    internal fun update(change: (PhonePlayback) -> PhonePlayback) = _state.update(change)

    fun listen(context: Context) = send(context, PlaybackService.LISTEN)
    fun stopListening(context: Context) = send(context, PlaybackService.STOP_LISTENING)
    fun playLocal(context: Context, ids: List<String>, index: Int = 0) =
        send(context, PlaybackService.PLAY_LOCAL) { putExtra("ids", ids.toTypedArray()); putExtra("index", index) }

    /** The downloaded-songs player, for transport controls. */
    fun local(): Player? = PlaybackService.instance?.exo

    private fun send(context: Context, action: String, extras: Intent.() -> Unit = {}) {
        context.startService(Intent(context, PlaybackService::class.java).setAction(action).apply(extras))
    }
}

@UnstableApi
class PlaybackService : MediaSessionService(), LivePlayer.Controls {
    companion object {
        const val LISTEN = "pianobar.listen"
        const val STOP_LISTENING = "pianobar.stop_listening"
        const val PLAY_LOCAL = "pianobar.play_local"
        @Volatile var instance: PlaybackService? = null
            private set
    }

    private val app get() = application as PianobarApp
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private lateinit var live: LivePlayer
    lateinit var exo: ExoPlayer
        private set
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private lateinit var focus: AudioFocusRequest
    private var stream: LiveStream? = null
    private var retained = false
    private var noisyRegistered = false

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { Log.i(TAG, "headphones unplugged; stop listening"); stopListening() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(PlatformAttributes.Builder().setUsage(PlatformAttributes.USAGE_MEDIA)
                .setContentType(PlatformAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                val current = stream ?: return@setOnAudioFocusChangeListener
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> { current.duck = 1f; current.held = false }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> current.duck = .2f
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> current.held = true
                    else -> { Log.i(TAG, "audio focus lost ($change); stop listening"); stopListening() }
                }
            }.build()
        live = LivePlayer(Looper.getMainLooper(), this)
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { publishLocal() }
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, live).setSessionActivity(open).build()
        // Show the media notification without waiting for a controller to connect.
        addSession(session)
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_note) })
        scope.launch {
            app.repo.snapshot.collect { snapshot ->
                live.update(snapshot)
                val state = snapshot?.state ?: return@collect
                val art = app.repo.artUrl(state.cachedCover.ifEmpty { state.metadata?.cover?.ifEmpty { null } ?: state.cover })
                if (art != live.artworkKey) loadArtwork(art)
            }
        }
        // Position for the local player's progress bar.
        scope.launch {
            while (isActive) { if (exo.isPlaying) publishLocal(); delay(500) }
        }
    }

    private fun loadArtwork(url: String?) {
        live.setArtwork(url ?: "", null)
        if (url == null) return
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try {
                    val api = app.repo.api.value ?: return@withContext null
                    api.client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                        if (response.code == 200) response.body?.bytes()?.takeIf { it.size < 8 * 1024 * 1024 } else null
                    }
                } catch (e: Exception) { null }
            }
            if (live.artworkKey == url) live.setArtwork(url, bytes)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            LISTEN -> startListening()
            STOP_LISTENING -> { Log.i(TAG, "stop listening requested"); stopListening() }
            PLAY_LOCAL -> playLocal(intent.getStringArrayExtra("ids")?.toList() ?: emptyList(), intent.getIntExtra("index", 0))
        }
        return super.onStartCommand(intent, flags, startId)
    }

    // ---- Live stream -----------------------------------------------------------------------

    override fun startListening() {
        if (stream != null) return
        val api = app.repo.api.value ?: run { Playback.update { it.copy(message = "Choose your server first.") }; return }
        if (exo.isPlaying || exo.mediaItemCount > 0) { exo.stop(); exo.clearMediaItems() }
        session.player = live
        audio.requestAudioFocus(focus)
        if (!noisyRegistered) {
            ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            noisyRegistered = true
        }
        if (!retained) { app.repo.retain(); retained = true }
        val created = LiveStream(api, app.repo.store.deviceName) { status, message ->
            main.post {
                live.setListening(stream != null, status == StreamStatus.Playing || status == StreamStatus.Waiting)
                Playback.update { it.copy(stream = status, message = message) }
                if (status == StreamStatus.SignInRequired) { app.repo.reconnect(); stopListening() }
            }
        }
        stream = created
        created.start()
        live.setListening(true, false)
        Playback.update { it.copy(listening = true, local = false, stream = StreamStatus.Connecting, message = "") }
    }

    override fun stopListening() {
        val current = stream ?: return
        stream = null
        current.stop()
        audio.abandonAudioFocusRequest(focus)
        if (noisyRegistered) { unregisterReceiver(noisy); noisyRegistered = false }
        if (retained) { app.repo.release(); retained = false }
        live.setListening(false, false)
        Playback.update { it.copy(listening = false, stream = StreamStatus.Stopped) }
        // Media3 stops the foreground service once nothing plays; stopping the service
        // here could race its own startForeground and crash the app.
    }

    override fun serverPlay(play: Boolean) = app.repo.action("act_songpausetoggle")
    override fun serverNext() = app.repo.action("act_songnext")

    // ---- Downloaded songs ------------------------------------------------------------------

    private fun playLocal(ids: List<String>, index: Int) {
        val library = app.phone
        val tracks = ids.mapNotNull { id -> library.tracks.value.firstOrNull { it.id == id } }
        if (tracks.isEmpty()) return
        stopListening()
        val items = tracks.map { track ->
            MediaItem.Builder().setMediaId(track.id).setUri(Uri.fromFile(library.file(track.id)))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.label).setArtist(track.artist).setAlbumTitle(track.album)
                    .apply { library.cover(track.id)?.let { setArtworkUri(Uri.fromFile(it)) } }.build())
                .build()
        }
        session.player = exo
        exo.setMediaItems(items, index.coerceIn(0, items.size - 1), 0)
        exo.prepare()
        exo.play()
        Playback.update { it.copy(local = true, localQueue = tracks.map { t -> t.id }) }
    }

    private fun publishLocal() {
        Playback.update {
            it.copy(
                localTrack = exo.currentMediaItem?.mediaId ?: "",
                localPlaying = exo.isPlaying,
                localIndex = exo.currentMediaItemIndex,
                positionMs = exo.currentPosition,
                durationMs = exo.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0,
                local = exo.mediaItemCount > 0 && stream == null,
            )
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session.player
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE) stopSelf()
    }

    override fun onDestroy() {
        stopListening()
        instance = null
        scope.cancel()
        session.release()
        exo.release()
        live.release()
        Playback.update { PhonePlayback() }
        super.onDestroy()
    }
}
