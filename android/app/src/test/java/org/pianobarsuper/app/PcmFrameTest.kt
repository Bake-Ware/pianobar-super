package org.pianobarsuper.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.pianobarsuper.app.net.PcmFrame
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.IOException

class PcmFrameTest {
    private fun frame(size: Int, rate: Int, channels: Int, little: Int, payload: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply { writeInt(size); writeInt(rate); writeInt(channels); writeInt(little); writeInt(7); write(payload) }
        return bytes.toByteArray()
    }

    private fun parse(data: ByteArray) = PcmFrame.read(DataInputStream(ByteArrayInputStream(data)))

    @Test fun bothByteOrdersAndFragmentedReads() {
        assertArrayEquals(shortArrayOf(-32768, 32767), parse(frame(4, 44100, 2, 1, byteArrayOf(0, -128, -1, 127))).samples)
        val data = frame(4, 48000, 1, 0, byteArrayOf(-128, 0, 127, -1))
        val fragmented = object : FilterInputStream(ByteArrayInputStream(data)) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(1, len))
        }
        val f = PcmFrame.read(DataInputStream(fragmented))
        assertArrayEquals(shortArrayOf(-32768, 32767), f.samples)
        assertEquals(48000, f.rate)
        assertEquals(7, f.epoch)
    }

    @Test fun keepaliveAndMalformedFrames() {
        assertEquals(0, parse(ByteArray(20)).samples.size)
        for (bad in listOf(frame(-1, 44100, 2, 1, ByteArray(0)), frame(10000000, 44100, 2, 1, ByteArray(0)),
            frame(2, 44100, 2, 1, ByteArray(2)), frame(4, 44100, 8, 1, ByteArray(4)), frame(4, 0, 2, 1, ByteArray(4)),
            frame(4, 44100, 2, 5, ByteArray(4)), frame(4, 44100, 2, 1, ByteArray(3)), ByteArray(19))) {
            assertThrows(IOException::class.java) { parse(bad) }
        }
    }
}
