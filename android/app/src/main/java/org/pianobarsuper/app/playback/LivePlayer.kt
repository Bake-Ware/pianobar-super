package org.pianobarsuper.app.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.pianobarsuper.app.net.Snapshot

/**
 * The server's radio as a Media3 player, so the lock screen, notification,
 * Bluetooth and headset buttons work. Play/pause and next act on the server;
 * stop ends listening on this phone. The server's playlist is live, so there
 * is one item (the current song) and no seeking.
 */
@UnstableApi
class LivePlayer(looper: Looper, private val controls: Controls) : SimpleBasePlayer(looper) {
    interface Controls {
        fun startListening()
        fun stopListening()
        fun serverPlay(play: Boolean)
        fun serverNext()
    }

    private var snapshot: Snapshot? = null
    private var listening = false
    private var connected = false
    private var artwork: ByteArray? = null
    var artworkKey = ""
        private set

    fun update(snapshot: Snapshot?) { this.snapshot = snapshot; invalidateState() }
    fun setListening(listening: Boolean, connected: Boolean) { this.listening = listening; this.connected = connected; invalidateState() }
    fun setArtwork(key: String, bytes: ByteArray?) { artworkKey = key; artwork = bytes; invalidateState() }

    override fun getState(): State {
        val state = snapshot?.state
        val commands = Player.Commands.Builder().addAll(
            COMMAND_PLAY_PAUSE, COMMAND_STOP, COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_METADATA, COMMAND_GET_TIMELINE, COMMAND_PREPARE, COMMAND_RELEASE,
        ).build()
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(state?.paused != true, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        val hasSong = state != null && state.title.isNotEmpty()
        // Media3 allows an empty playlist only while idle.
        builder.setPlaybackState(when {
            !listening || !hasSong -> STATE_IDLE
            connected -> STATE_READY
            else -> STATE_BUFFERING
        })
        if (state != null && hasSong) {
            val metadata = MediaMetadata.Builder()
                .setTitle(state.title).setArtist(state.artist).setAlbumTitle(state.album)
                .setStation(snapshot?.playlist?.name ?: state.station)
                .setIsPlayable(true)
                .apply { artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
                .build()
            val item = MediaItemData.Builder(state.songKey.ifEmpty { state.title })
                .setMediaItem(MediaItem.Builder().setMediaId(state.songKey.ifEmpty { "live" }).setMediaMetadata(metadata).build())
                .setMediaMetadata(metadata)
                .setDurationUs(if (state.duration > 0) state.duration * 1_000_000L else androidx.media3.common.C.TIME_UNSET)
                .setIsSeekable(false)
                .build()
            builder.setPlaylist(listOf(item))
            val elapsed = state.elapsed * 1000L
            builder.setContentPositionMs(
                if (state.paused || !listening) PositionSupplier.getConstant(elapsed)
                else PositionSupplier.getExtrapolating(elapsed, 1f)
            )
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady && !listening) controls.startListening()
        val paused = snapshot?.state?.paused == true
        if (playWhenReady == paused) controls.serverPlay(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (!listening) controls.startListening()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controls.stopListening()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (seekCommand == COMMAND_SEEK_TO_NEXT || seekCommand == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) controls.serverNext()
        return Futures.immediateVoidFuture()
    }
}
