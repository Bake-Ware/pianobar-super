package org.pianobarsuper.app.widget

import org.pianobarsuper.app.data.Connection
import org.pianobarsuper.app.net.Snapshot
import org.pianobarsuper.app.playback.PhonePlayback

/** What the home-screen widget shows, worked out from the same state the app's screens use. */
data class WidgetContent(
    val title: String,
    val artist: String = "",
    val station: String = "",
    val status: String = "",
    val listening: Boolean = false,
    val loved: Boolean = false,
    val canLove: Boolean = false,
    val canNext: Boolean = false,
    /** Server path or URL of the cover, as in the snapshot. */
    val art: String = "",
) {
    companion object {
        fun from(snapshot: Snapshot?, phone: PhonePlayback, connection: Connection): WidgetContent {
            val listeningStatus = if (phone.listening) "Listening on this phone" else "Not listening here"
            return when {
                connection is Connection.NoServer -> WidgetContent("pianobar", "Open the app to choose your server")
                connection is Connection.SignInRequired -> WidgetContent("pianobar", "Sign in to your server", status = "Tap to open the app")
                snapshot == null -> WidgetContent("pianobar", if (connection is Connection.Unreachable) "Can’t reach your server" else "Not connected",
                    status = if (phone.listening) listeningStatus else "Tap to open the app", listening = phone.listening)
                else -> {
                    val state = snapshot.state
                    val playing = state.title.isNotEmpty()
                    WidgetContent(
                        title = if (playing) state.title else "Nothing playing",
                        artist = if (playing) state.artist else if (snapshot.setupRequired) "Finish setting up your server" else "Pick a station in the app",
                        station = snapshot.playlist?.name?.ifEmpty { null } ?: state.station,
                        status = listOfNotNull(listeningStatus, "Paused".takeIf { state.paused && playing },
                            "Offline".takeIf { connection is Connection.Unreachable }).joinToString(" · "),
                        listening = phone.listening,
                        loved = state.loved,
                        canLove = state.can("act_songlove"),
                        canNext = state.can("act_songnext"),
                        art = if (playing) state.cachedCover.ifEmpty { state.metadata?.cover?.ifEmpty { null } ?: state.cover } else "",
                    )
                }
            }
        }
    }
}
