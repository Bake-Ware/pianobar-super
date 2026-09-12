package org.pianobarsuper.app;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.session.*;
import android.net.wifi.WifiManager;
import android.os.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Native live playback: networking, leases and PCM output never depend on the activity. */
public final class StreamPlayerService extends Service {
    static final String PLAY = "stream.play", PAUSE = "stream.pause", TOGGLE = "stream.toggle",
            NEXT = "stream.next", STOP = "stream.stop";
    static volatile String status = "Sign in, select a station, then tap Listen.", title = "pianobar";
    static volatile boolean active, paused;
    static volatile long samplesWritten;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService network = Executors.newSingleThreadScheduledExecutor();
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private volatile Run run;
    private boolean resumeOnFocus, destroyed;
    private volatile float duck = 1;
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { resumeOnFocus = false; pause(); }
    };
    private final class Run {
        final NativeConnection connection;
        volatile boolean cancelled;
        volatile String client = "";
        volatile float volume = 1;
        volatile boolean enabled = true;
        volatile HttpURLConnection stream;
        Thread thread;
        Run(String origin) { connection = new NativeConnection(origin); }
        void cancel() {
            cancelled = true;
            if (stream != null) stream.disconnect();
            if (thread != null) thread.interrupt();
        }
    }
    @Override public void onCreate() {
        super.onCreate();
        audio = (AudioManager)getSystemService(AUDIO_SERVICE);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes())
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_GAIN) {
                        duck = 1; if (resumeOnFocus) resume(); resumeOnFocus = false;
                    } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) duck = .2f;
                    else { resumeOnFocus = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && !paused; pause(); }
                }).build();
        wake = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pianobar:stream");
        wifi = ((WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pianobar:stream");
        session = new MediaSession(this, "pianobar streaming");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { resumeOnFocus = false; pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onStop() { stopSelf(); }
        });
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setActive(true);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("stream", "Live music", NotificationManager.IMPORTANCE_LOW));
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        network.scheduleWithFixedDelay(this::heartbeat, 1, 15, TimeUnit.SECONDS);
    }
    private AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        startForeground(2, notification());
        if (intent == null || STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (!PLAY.equals(action) && run == null) { stopSelf(); return START_NOT_STICKY; }
        if (PLAY.equals(action)) {
            String origin = getSharedPreferences("connection", MODE_PRIVATE).getString("server", "");
            try {
                origin = ServerAddress.normalize(origin);
                if (run == null || !run.connection.origin.equals(origin)) {
                    if (run != null) run.cancel();
                    stopService(new Intent(this, OfflinePlayerService.class));
                    run = new Run(origin); active = true; paused = true;
                    status = "Connecting…";
                    Run current = run;
                    current.thread = new Thread(() -> stream(current), "pianobar-pcm");
                    current.thread.start();
                }
                resume();
            } catch (IllegalArgumentException e) { status = "Set a valid server in Settings."; stopSelf(); }
        } else if (PAUSE.equals(action)) { resumeOnFocus = false; pause(); }
        else if (TOGGLE.equals(action)) { resumeOnFocus = false; if (paused) resume(); else pause(); }
        else if (NEXT.equals(action)) next();
        return START_NOT_STICKY;
    }
    private void resume() {
        if (run == null) return;
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            status = "Audio focus unavailable. Tap Listen to retry."; update(); return;
        }
        paused = false;
        if (!wake.isHeld()) wake.acquire();
        if (!wifi.isHeld()) wifi.acquire();
        status = "Listening"; update();
    }
    private void pause() {
        paused = true; status = "Paused on this device";
        if (wake.isHeld()) wake.release();
        if (wifi.isHeld()) wifi.release();
        update();
    }
    private void next() {
        Run current = run;
        if (current == null) return;
        network.execute(() -> {
            try { current.connection.json("api/command", new JSONObject().put("action", "act_songnext")); }
            catch (Exception e) { main.post(() -> { status = "Could not skip. Check the player."; update(); }); }
        });
    }
    private void heartbeat() {
        Run current = run;
        if (current == null || current.cancelled || current.client.isEmpty()) return;
        try {
            JSONObject page = current.connection.json("api/clients/heartbeat", new JSONObject()
                    .put("id", current.client).put("status", paused ? "idle" : "playing").put("ready", !paused));
            current.volume = (float)page.optDouble("volume", 1);
            current.enabled = page.optBoolean("enabled", true);
            JSONObject state = current.connection.json("api/state", null).optJSONObject("state");
            if (state != null) main.post(() -> {
                if (run != current || destroyed) return;
                title = state.optString("title", "pianobar");
                session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, state.optString("artist")).build());
                update();
            });
        } catch (NativeConnection.LoginRequired e) { loginRequired(current); }
        catch (Exception e) { if (current.stream != null) current.stream.disconnect(); }
    }
    private void loginRequired(Run current) {
        main.post(() -> {
            if (run != current || destroyed) return;
            status = "Session expired. Open Player, sign in, then tap Listen.";
            stopSelf();
        });
    }
    private void stream(Run current) {
        int retry = 1;
        while (!current.cancelled) {
            AudioTrack output = null;
            String registered = "";
            try {
                registered = current.connection.json("api/clients/register", new JSONObject().put("name", "Android native")).getString("id");
                current.client = registered;
                current.connection.json("api/clients/update", new JSONObject().put("id", registered).put("enabled", true));
                JSONObject state = current.connection.json("api/state", null).optJSONObject("state");
                if (state != null && "host".equals(state.optString("output")))
                    current.connection.json("api/command", new JSONObject().put("output", "both"));
                HttpURLConnection c = current.connection.open("api/audio");
                current.stream = c;
                if (current.cancelled) break;
                c.setRequestProperty("X-Pianobar-Client", registered);
                NativeConnection.check(c);
                main.post(() -> { if (run == current && !destroyed) { status = paused ? "Paused on this device" : "Listening"; update(); } });
                if (!"application/octet-stream".equals(c.getContentType())) throw new NativeConnection.LoginRequired();
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(c.getInputStream()))) {
                    int rate = 0, channels = 0, epoch = -1;
                    boolean wasMuted = false;
                    while (!current.cancelled) {
                        PcmFrame frame = PcmFrame.read(in);
                        retry = 1;
                        boolean muted = paused || !current.enabled;
                        if (muted && !wasMuted && output != null) { output.pause(); output.flush(); }
                        wasMuted = muted;
                        if (frame.samples.length == 0 || muted) continue;
                        if (output == null || rate != frame.rate || channels != frame.channels) {
                            if (output != null) output.release();
                            rate = frame.rate; channels = frame.channels;
                            int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
                            int minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
                            if (minimum <= 0) throw new IOException("Unsupported audio format");
                            output = new AudioTrack.Builder().setAudioAttributes(attributes())
                                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                                    .setBufferSizeInBytes(Math.max(minimum, rate * channels / 2)).setTransferMode(AudioTrack.MODE_STREAM).build();
                            epoch = frame.epoch;
                        }
                        if (epoch != frame.epoch) { output.pause(); output.flush(); epoch = frame.epoch; }
                        output.setVolume(current.volume * duck);
                        output.play();
                        for (int offset = 0; offset < frame.samples.length && !current.cancelled && !paused && current.enabled;) {
                            int written = output.write(frame.samples, offset, frame.samples.length - offset, AudioTrack.WRITE_NON_BLOCKING);
                            if (written < 0) throw new IOException("Audio output unavailable");
                            offset += written; samplesWritten += written;
                            if (written == 0) Thread.sleep(10);
                        }
                    }
                }
            } catch (NativeConnection.LoginRequired e) { loginRequired(current); break; }
            catch (Exception e) {
                if (!current.cancelled) {
                    main.post(() -> { if (run == current && !destroyed) { status = "Connection interrupted. Reconnecting…"; update(); } });
                }
            } finally {
                if (output != null) output.release();
                if (current.stream != null) current.stream.disconnect();
                current.stream = null; current.client = "";
                if (!registered.isEmpty()) try {
                    current.connection.json("api/clients/unregister", new JSONObject().put("id", registered));
                } catch (Exception ignored) {}
            }
            if (!current.cancelled) try { Thread.sleep(retry * 1000L); retry = Math.min(15, retry * 2); }
            catch (InterruptedException e) { break; }
        }
    }
    private PendingIntent action(String action) {
        return PendingIntent.getService(this, action.hashCode(), new Intent(this, StreamPlayerService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, PlayerActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "stream").setSmallIcon(R.drawable.ic_note).setContentTitle(title)
                .setContentText(status).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(active && !paused)
                .addAction(new Notification.Action.Builder(paused ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause,
                        paused ? "Play" : "Pause", action(TOGGLE)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", action(NEXT)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", action(STOP)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2)).build();
    }
    private void update() {
        if (destroyed) return;
        session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_STOP)
                .setState(paused ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, paused ? 0 : 1).build());
        getSystemService(NotificationManager.class).notify(2, notification());
    }
    @Override public void onDestroy() {
        destroyed = true; if (run != null) run.cancel(); run = null;
        network.shutdownNow(); main.removeCallbacksAndMessages(null);
        audio.abandonAudioFocusRequest(focus);
        if (wake.isHeld()) wake.release();
        if (wifi.isHeld()) wifi.release();
        unregisterReceiver(noisy); session.release(); active = false; paused = true;
        if (!status.startsWith("Session expired") && !status.startsWith("Set a valid")) status = "Stopped. Tap Listen to reconnect.";
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
