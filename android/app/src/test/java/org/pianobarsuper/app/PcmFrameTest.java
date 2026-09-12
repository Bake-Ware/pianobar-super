package org.pianobarsuper.app;
import java.io.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class PcmFrameTest {
    private byte[] frame(int size, int rate, int channels, int little, byte[] payload) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(size); out.writeInt(rate); out.writeInt(channels); out.writeInt(little); out.writeInt(7); out.write(payload);
        return bytes.toByteArray();
    }
    private PcmFrame parse(byte[] data) throws IOException { return PcmFrame.read(new DataInputStream(new ByteArrayInputStream(data))); }
    @Test public void bothByteOrdersAndFragmentedReads() throws Exception {
        assertArrayEquals(new short[]{-32768,32767}, parse(frame(4,44100,2,1,new byte[]{0,-128,-1,127})).samples);
        byte[] data = frame(4,48000,1,0,new byte[]{-128,0,127,-1});
        InputStream fragmented = new FilterInputStream(new ByteArrayInputStream(data)) {
            @Override public int read(byte[] b, int off, int len) throws IOException { return super.read(b, off, Math.min(1,len)); }
        };
        PcmFrame f = PcmFrame.read(new DataInputStream(fragmented));
        assertArrayEquals(new short[]{-32768,32767}, f.samples); assertEquals(48000,f.rate); assertEquals(7,f.epoch);
    }
    @Test public void keepaliveAndMalformedFrames() throws Exception {
        assertEquals(0,parse(new byte[20]).samples.length);
        for (byte[] bad : new byte[][]{
                frame(-1,44100,2,1,new byte[0]), frame(10000000,44100,2,1,new byte[0]),
                frame(2,44100,2,1,new byte[2]), frame(4,44100,8,1,new byte[4]),
                frame(4,0,2,1,new byte[4]), frame(4,44100,2,5,new byte[4]),
                frame(4,44100,2,1,new byte[3]), new byte[19]})
            assertThrows(IOException.class, () -> parse(bad));
    }
}
