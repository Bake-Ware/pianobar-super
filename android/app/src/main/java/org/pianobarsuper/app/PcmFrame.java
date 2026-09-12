package org.pianobarsuper.app;

import java.io.*;
/** Bounded parser for pianobar's network-order header and signed 16-bit PCM. */
final class PcmFrame {
    final int rate, channels, epoch;
    final short[] samples;
    private PcmFrame(int rate, int channels, int epoch, short[] samples) {
        this.rate = rate; this.channels = channels; this.epoch = epoch; this.samples = samples;
    }
    static PcmFrame read(DataInputStream input) throws IOException {
        int size = input.readInt(), rate = input.readInt(), channels = input.readInt();
        int little = input.readInt(), epoch = input.readInt();
        if (size == 0 && rate == 0 && channels == 0 && little == 0 && epoch == 0)
            return new PcmFrame(0, 0, 0, new short[0]);
        if (size <= 0 || size > 262124 || rate < 8000 || rate > 192000 ||
                channels < 1 || channels > 2 || size % (channels * 2) != 0 || (little != 0 && little != 1))
            throw new IOException("Unsupported audio frame");
        byte[] data = new byte[size]; input.readFully(data);
        short[] samples = new short[size / 2];
        for (int i = 0; i < samples.length; i++) {
            int a = data[i * 2] & 255, b = data[i * 2 + 1] & 255;
            samples[i] = (short)(little == 1 ? a | (b << 8) : (a << 8) | b);
        }
        return new PcmFrame(rate, channels, epoch, samples);
    }
}
