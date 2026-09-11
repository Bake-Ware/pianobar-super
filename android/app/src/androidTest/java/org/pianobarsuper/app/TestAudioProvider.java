package org.pianobarsuper.app;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.nio.*;

/** Test APK only: provides a real local WAV through Android's content resolver. */
public final class TestAudioProvider extends ContentProvider {
    private File file;
    @Override public boolean onCreate() {
        file = new File(getContext().getCacheDir(), "test.wav");
        try {
            int samples = 44100 * 15;
            ByteBuffer data = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
            data.put("RIFF".getBytes()).putInt(36 + samples * 2).put("WAVEfmt ".getBytes()).putInt(16)
                    .putShort((short)1).putShort((short)1).putInt(44100).putInt(88200).putShort((short)2).putShort((short)16)
                    .put("data".getBytes()).putInt(samples * 2);
            for (int i = 0; i < samples; ++i) data.putShort((short)(Math.sin(i * 2 * Math.PI * 440 / 44100) * 5000));
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(data.array()); }
            return true;
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException { return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY); }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME}); cursor.addRow(new Object[]{"Offline test.wav"}); return cursor;
    }
    @Override public String getType(Uri uri) { return "audio/wav"; }
    @Override public int delete(Uri uri, String s, String[] a) { return file.delete() ? 1 : 0; }
    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
