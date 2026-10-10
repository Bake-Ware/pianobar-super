package org.pianobarsuper.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pianobarsuper.app.playback.ParkSerial

class ParkSerialTest {
    @Test fun aStopAndStartBetweenTwoFramesIsStillNoticed() {
        val parkings = ParkSerial()
        assertFalse(parkings.pending())
        assertFalse(parkings.consume())
        // park() then resume() before the reader looks: parked is false again, but the
        // track was paused, so the reader must still re-prime it.
        parkings.bump()
        parkings.bump()
        assertTrue(parkings.pending())
        assertTrue(parkings.consume())
        assertFalse(parkings.pending())
        assertFalse(parkings.consume())
    }
}
