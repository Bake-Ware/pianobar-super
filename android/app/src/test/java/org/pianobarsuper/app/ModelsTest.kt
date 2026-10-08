package org.pianobarsuper.app

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pianobarsuper.app.net.Snapshot
import org.pianobarsuper.app.net.json
import org.pianobarsuper.app.net.title
import org.pianobarsuper.app.ui.spokenText

class ModelsTest {
    @Test fun parsesASnapshotAndToleratesNewFields() {
        val snapshot = json.decodeFromString<Snapshot>("""
            {"revision":5413,"future":{"x":1},"state":{"type":"state","offline":true,"volume":-3,"output":"browser","paused":false,
             "elapsed":240,"duration":302,"station":"Offline library","stations":[{"id":"1","name":"QuickMix","quickMix":true}],
             "savedId":"a.mka","queuedSavedIds":["b.mka"],"title":"Breathe","artist":"The Prodigy","cachedCover":"/api/artwork/ab",
             "actions":[{"id":"act_songnext","label":"next song","key":"n","enabled":true},{"id":"act_songlove","key":"+","enabled":false}],
             "metadata":{"status":"found","genres":["big beat"],"recordingId":"0f2ec8d4-5c4a-4f43-9d0f-0b1f8a2a1a11"},"songKey":"k"},
             "prompt":{"active":false,"id":0},"output":"…","pending":false,
             "djStation":{"enabled":true,"status":"playing_set","next":{"id":"b.mka","title":"Spitfire","artist":"Porter Robinson"},
               "hopNext":null,"settings":{"dj_name":"DJ Fawkes","listener_name":"Bake"},
               "currentSet":{"songs":[{"id":"a.mka","title":"Breathe","artist":"The Prodigy","duration":302}],"position":1,"duration":302},
               "llmReady":true,"voiceReady":true},
             "djVoice":{"id":7,"status":"done","text":"Hey Bake [chuckle] here we go","songKey":"k"},
             "playlist":{"id":"p1","name":"Night drive","position":0,"total":2,"shuffle":false}}""")
        assertEquals(5413, snapshot.revision)
        assertEquals("Breathe", snapshot.state.title)
        assertTrue(snapshot.state.can("act_songnext"))
        assertFalse(snapshot.state.can("act_songlove"))
        assertEquals("Next song", snapshot.state.action("act_songnext")!!.title())
        assertEquals("DJ Fawkes", snapshot.djStation!!.settings.dj_name)
        assertEquals("Night drive", snapshot.playlist!!.name)
        assertEquals(listOf("big beat"), snapshot.state.metadata!!.genres)
    }

    @Test fun soundTagsReadAsStageDirections() {
        assertEquals("(laughs) Hi there (clears throat)", spokenText("[Laugh] Hi [banana] there [clear throat]"))
        assertEquals("Hey Bake (chuckles) here we go", spokenText("Hey Bake [chuckle] here we go"))
    }
}
