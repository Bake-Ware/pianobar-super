package org.pianobarsuper.app.net

import java.io.DataInputStream
import java.io.IOException

/** Bounded parser for pianobar's network-order header and signed 16-bit PCM. */
class PcmFrame(val rate: Int, val channels: Int, val epoch: Int, val samples: ShortArray) {
    companion object {
        @Throws(IOException::class)
        fun read(input: DataInputStream): PcmFrame {
            val size = input.readInt()
            val rate = input.readInt()
            val channels = input.readInt()
            val little = input.readInt()
            val epoch = input.readInt()
            if (size == 0 && rate == 0 && channels == 0 && little == 0 && epoch == 0) return PcmFrame(0, 0, 0, ShortArray(0))
            if (size <= 0 || size > 262124 || rate < 8000 || rate > 192000 || channels < 1 || channels > 2 ||
                size % (channels * 2) != 0 || (little != 0 && little != 1)
            ) throw IOException("Unsupported audio frame")
            val data = ByteArray(size)
            input.readFully(data)
            val samples = ShortArray(size / 2)
            for (i in samples.indices) {
                val a = data[i * 2].toInt() and 255
                val b = data[i * 2 + 1].toInt() and 255
                samples[i] = (if (little == 1) a or (b shl 8) else (a shl 8) or b).toShort()
            }
            return PcmFrame(rate, channels, epoch, samples)
        }
    }
}
