package org.pianobarsuper.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pianobarsuper.app.data.Connection
import org.pianobarsuper.app.net.PlayerAction
import org.pianobarsuper.app.net.PlayerState
import org.pianobarsuper.app.net.PlaylistRun
import org.pianobarsuper.app.net.Snapshot
import org.pianobarsuper.app.playback.PhonePlayback
import org.pianobarsuper.app.widget.WidgetContent

class WidgetContentTest {
    private val playing = Snapshot(revision = 3, state = PlayerState(title = "Breathe", artist = "The Prodigy", station = "Big beat radio",
        loved = true, cachedCover = "/api/artwork/ab", cover = "https://example.invalid/c.jpg",
        actions = listOf(PlayerAction("act_songnext", enabled = true), PlayerAction("act_songlove", enabled = false))))

    @Test fun showsTheSongAndListening() {
        val content = WidgetContent.from(playing, PhonePlayback(listening = true), Connection.Online)
        assertEquals("Breathe", content.title)
        assertEquals("The Prodigy", content.artist)
        assertEquals("Big beat radio", content.station)
        assertEquals("Listening on this phone", content.status)
        assertTrue(content.listening)
        assertTrue(content.loved)
        assertTrue(content.canNext)
        assertFalse(content.canLove)
        assertEquals("/api/artwork/ab", content.art)
    }

    @Test fun aPlaylistNamesTheStation() {
        val content = WidgetContent.from(playing.copy(playlist = PlaylistRun(name = "Night drive")), PhonePlayback(), Connection.Online)
        assertEquals("Night drive", content.station)
        assertEquals("Not listening here", content.status)
    }

    @Test fun idleStates() {
        assertEquals("Open the app to choose your server", WidgetContent.from(null, PhonePlayback(), Connection.NoServer).artist)
        assertEquals("Sign in to your server", WidgetContent.from(playing, PhonePlayback(), Connection.SignInRequired).artist)
        assertEquals("Can’t reach your server", WidgetContent.from(null, PhonePlayback(), Connection.Unreachable("x")).artist)
        val nothing = WidgetContent.from(Snapshot(), PhonePlayback(), Connection.Online)
        assertEquals("Nothing playing", nothing.title)
        assertEquals("", nothing.art)
        assertFalse(nothing.canNext)
    }

    @Test fun lastKnownSongWhenOffline() {
        val content = WidgetContent.from(playing.copy(state = playing.state.copy(paused = true)), PhonePlayback(), Connection.Unreachable("x"))
        assertEquals("Breathe", content.title)
        assertEquals("Not listening here · Paused · Offline", content.status)
    }
}
