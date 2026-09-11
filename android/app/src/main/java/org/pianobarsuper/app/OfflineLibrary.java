package org.pianobarsuper.app;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

final class OfflineLibrary {
    static final class Track {
        final String id, title, artist;
        Track(String id, String title, String artist) { this.id = id; this.title = title; this.artist = artist; }
    }
    private static final Object LOCK = new Object();
    private final Context context;
    private final File directory;
    OfflineLibrary(Context context) {
        this.context = context.getApplicationContext();
        directory = new File(context.getFilesDir(), "offline");
        directory.mkdirs();
    }
    File file(String id) {
        if (id == null || !id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid track");
        return new File(directory, id + ".audio");
    }
    List<Track> tracks() {
        synchronized (LOCK) {
            List<Track> result = new ArrayList<>();
            try {
                JSONArray list = new JSONArray(context.getSharedPreferences("library", 0).getString("tracks", "[]"));
                for (int i = 0; i < list.length(); i++) {
                    JSONObject entry = list.getJSONObject(i);
                    String id = entry.getString("id");
                    if (file(id).isFile()) result.add(new Track(id, entry.getString("title"), entry.optString("artist", "")));
                }
            } catch (Exception ignored) { /* Invalid entries are never used as filesystem paths. */ }
            return result;
        }
    }
    private void save(List<Track> tracks) throws Exception {
        JSONArray list = new JSONArray();
        for (Track t : tracks) list.put(new JSONObject().put("id", t.id).put("title", t.title).put("artist", t.artist));
        if (!context.getSharedPreferences("library", 0).edit().putString("tracks", list.toString()).commit())
            throw new IOException("Could not save the offline library.");
    }
    Track importTrack(Uri uri) throws Exception {
        if (!"content".equals(uri.getScheme())) throw new IOException("Choose an audio file from the file picker.");
        File temporary = File.createTempFile("import-", ".tmp", directory);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream input = context.getContentResolver().openInputStream(uri); OutputStream output = new FileOutputStream(temporary)) {
                if (input == null) throw new IOException("Cannot open the selected file.");
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 100L * 1024 * 1024) throw new IOException("Choose tracks smaller than 100 MB.");
                    output.write(buffer, 0, count); digest.update(buffer, 0, count);
                }
            }
            StringBuilder hash = new StringBuilder();
            for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
            String title, artist;
            MediaMetadataRetriever metadata = new MediaMetadataRetriever();
            try {
                metadata.setDataSource(temporary.getAbsolutePath());
                if (!"yes".equals(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)))
                    throw new IOException("That file does not contain playable audio.");
                title = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                artist = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            } finally { metadata.release(); }
            if (title == null || title.trim().isEmpty()) {
                try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                        new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) title = cursor.getString(0);
                }
            }
            Track track = new Track(hash.toString(), title == null ? "Saved track" : title, artist == null ? "" : artist);
            synchronized (LOCK) {
                List<Track> tracks = tracks();
                for (Track existing : tracks) if (existing.id.equals(track.id)) return existing;
                if (!temporary.renameTo(file(track.id))) throw new IOException("Could not store the downloaded track.");
                tracks.add(track);
                try { save(tracks); } catch (Exception error) { file(track.id).delete(); throw error; }
            }
            return track;
        } finally { temporary.delete(); }
    }
    void remove(String id) throws Exception {
        synchronized (LOCK) {
            List<Track> tracks = tracks();
            tracks.removeIf(t -> t.id.equals(id));
            save(tracks);
            file(id).delete();
        }
    }
}
