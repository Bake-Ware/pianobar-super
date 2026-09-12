package org.pianobarsuper.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_AUDIO = 41;
    private static final int PAPER = Color.rgb(16, 26, 26), INK = Color.rgb(227, 232, 222), MUTED = Color.rgb(155, 169, 159), ORANGE = Color.rgb(230, 139, 85);
    private static final ExecutorService IMPORTS = Executors.newSingleThreadExecutor();
    private SharedPreferences preferences;
    private OfflineLibrary library;
    private LinearLayout content;
    private TextView playbackStatus;
    private Button playbackToggle;
    private String view = "settings";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshPlayback = new Runnable() {
        @Override public void run() {
            if (playbackStatus != null) {
                playbackStatus.setText(OfflinePlayerService.currentTitle.isEmpty() ? "Choose a downloaded track to listen." :
                        OfflinePlayerService.currentTitle + "\n" + OfflinePlayerService.status);
                playbackToggle.setText(OfflinePlayerService.playing ? "Pause" : "Play");
                playbackToggle.setEnabled(!OfflinePlayerService.currentId.isEmpty());
            }
            handler.postDelayed(this, 500);
        }
    };
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("connection", MODE_PRIVATE);
        library = new OfflineLibrary(this);
        String requested = getIntent().getAction();
        String server = preferences.getString("server", "");
        render("offline".equals(requested) ? "downloads" : "settings");
        if (savedInstanceState == null && Intent.ACTION_MAIN.equals(requested) && !server.isEmpty() && preferences.getBoolean("auto_open", true)) openPlayer();
    }
    @Override protected void onResume() { super.onResume(); handler.post(refreshPlayback); }
    @Override protected void onPause() { handler.removeCallbacks(refreshPlayback); super.onPause(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setPadding(0, dp(8), 0, dp(8)); return t;
    }
    private Button button(String title, Runnable action) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setMinHeight(dp(48));
        b.setTextColor(INK); b.setOnClickListener(v -> action.run()); return b;
    }
    private void render(String selected) {
        view = selected; playbackStatus = null; playbackToggle = null;
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(PAPER);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(16) + insets.getSystemWindowInsetTop(),
                    dp(20) + insets.getSystemWindowInsetRight(), dp(16) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.addView(text("▥ pianobar.", 32, ORANGE));
        LinearLayout navigation = new LinearLayout(this);
        navigation.addView(button("Player", this::openPlayer), new LinearLayout.LayoutParams(0, -2, 1));
        navigation.addView(button("Downloads", () -> render("downloads")), new LinearLayout.LayoutParams(0, -2, 1));
        navigation.addView(button("Settings", () -> render("settings")), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(navigation);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (selected.equals("downloads")) downloads(); else settings();
    }
    private void settings() {
        content.addView(text("Your server", 24, INK));
        content.addView(text("Connect to any pianobar web server. Sign in inside Player, then tap Listen for native background audio.", 14, MUTED));
        EditText server = new EditText(this); server.setSingleLine(true); server.setTextColor(INK); server.setTextSize(16);
        server.setHint("https://radio.example.com"); server.setHintTextColor(MUTED);
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(preferences.getString("server", "")); server.setContentDescription("Server address");
        content.addView(server);
        CheckBox auto = new CheckBox(this); auto.setText(R.string.open_on_launch); auto.setTextColor(INK);
        auto.setChecked(preferences.getBoolean("auto_open", true)); content.addView(auto);
        content.addView(button("Save server", () -> {
            try {
                String address = ServerAddress.normalize(server.getText().toString());
                preferences.edit().putString("server", address).putBoolean("auto_open", auto.isChecked()).apply();
                server.setText(address); Toast.makeText(this, "Server saved", Toast.LENGTH_SHORT).show();
            } catch (IllegalArgumentException error) { server.setError(error.getMessage()); }
        }));
        content.addView(button("Open player", this::openPlayer));
        content.addView(text("HTTPS works over the Internet. Local servers can use HTTP, for example http://192.168.1.10:8765.", 13, MUTED));
        content.addView(text("Audio runs in an Android media service and continues with the screen locked. Sign in inside the app; existing browser sessions are separate. Web passwords stay in memory until the app exits.", 13, MUTED));
        content.addView(text("pianobar · " + BuildConfig.VERSION_NAME, 12, MUTED));
    }
    private void openPlayer() {
        String address = preferences.getString("server", "");
        if (address.isEmpty()) { render("settings"); Toast.makeText(this, "Enter and save your server address first", Toast.LENGTH_LONG).show(); return; }
        startActivity(new Intent(this, PlayerActivity.class));
    }

    private void downloads() {
        content.addView(text("On this device", 24, INK));
        content.addView(text("Download tracks from the server's Library, then add those files here. Imported copies play without a server connection.", 14, MUTED));
        content.addView(button("Add downloaded tracks", () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");
            pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try { startActivityForResult(pick, PICK_AUDIO); }
            catch (ActivityNotFoundException error) { Toast.makeText(this, "No file picker is installed.", Toast.LENGTH_LONG).show(); }
        }));
        playbackStatus = text("", 16, INK); content.addView(playbackStatus);
        LinearLayout controls = new LinearLayout(this);
        controls.addView(button("Previous", () -> control(OfflinePlayerService.PREVIOUS, null)), new LinearLayout.LayoutParams(0, -2, 1));
        playbackToggle = button("Play", () -> control(OfflinePlayerService.TOGGLE, null));
        controls.addView(playbackToggle, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(button("Next", () -> control(OfflinePlayerService.NEXT, null)), new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(controls);
        content.addView(button("Stop", () -> stopService(new Intent(this, OfflinePlayerService.class))));
        List<OfflineLibrary.Track> tracks = library.tracks();
        if (tracks.isEmpty()) content.addView(text("No tracks on this device yet.", 14, MUTED));
        for (OfflineLibrary.Track track : tracks) {
            content.addView(text(track.title, 18, INK));
            if (!track.artist.isEmpty()) content.addView(text(track.artist, 13, MUTED));
            LinearLayout actions = new LinearLayout(this);
            actions.addView(button("Play track", () -> control(OfflinePlayerService.PLAY, track.id)), new LinearLayout.LayoutParams(0, -2, 1));
            actions.addView(button("Remove", () -> new AlertDialog.Builder(this).setTitle("Remove downloaded track?")
                    .setMessage("Remove this app's copy of “" + track.title + "”? Your original download and server library are kept.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Remove", (dialog, which) -> {
                        if (track.id.equals(OfflinePlayerService.currentId)) stopService(new Intent(this, OfflinePlayerService.class));
                        try { library.remove(track.id); render("downloads"); }
                        catch (Exception error) { Toast.makeText(this, "Could not remove this track.", Toast.LENGTH_LONG).show(); }
                    }).show()), new LinearLayout.LayoutParams(0, -2, 1));
            content.addView(actions);
        }
    }
    private void control(String action, String id) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        stopService(new Intent(this, StreamPlayerService.class));
        Intent intent = new Intent(this, OfflinePlayerService.class).setAction(action);
        if (id != null) intent.putExtra("id", id);
        startForegroundService(intent);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_AUDIO || resultCode != RESULT_OK || data == null) return;
        List<Uri> selected = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < Math.min(100, data.getClipData().getItemCount()); i++) selected.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) selected.add(data.getData());
        Toast.makeText(this, "Adding downloaded tracks…", Toast.LENGTH_SHORT).show();
        Context app = getApplicationContext();
        IMPORTS.execute(() -> {
            int added = 0, failed = 0;
            for (Uri uri : selected) {
                try { new OfflineLibrary(app).importTrack(uri); added++; } catch (Exception error) { failed++; }
            }
            String message = added + " track(s) added" + (failed == 0 ? "" : "; " + failed + " could not be imported");
            runOnUiThread(() -> {
                Toast.makeText(app, message, Toast.LENGTH_LONG).show();
                if (!isFinishing() && !isDestroyed() && view.equals("downloads")) render("downloads");
            });
        });
    }
}
