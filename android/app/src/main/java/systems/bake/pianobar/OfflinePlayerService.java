package systems.bake.pianobar;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.session.*;
import android.os.IBinder;
import java.util.List;

/** Plays only app-local imports; the server/browser remains a separate source. */
public final class OfflinePlayerService extends Service {
    static final String PLAY = "play", TOGGLE = "toggle", NEXT = "next", PREVIOUS = "previous", STOP = "stop";
    static volatile String currentId = "", currentTitle = "", status = "";
    static volatile boolean playing = false;
    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private boolean prepared, resumeOnFocus;
    private OfflineLibrary library;
    private OfflineLibrary.Track track;

    @Override public void onCreate() {
        super.onCreate();
        library = new OfflineLibrary(this);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_GAIN) {
                        if (player != null) player.setVolume(1, 1);
                        if (resumeOnFocus) resume();
                        resumeOnFocus = false;
                    } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                        if (player != null) player.setVolume(.2f, .2f);
                    } else {
                        resumeOnFocus = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && playing;
                        pause();
                    }
                }).build();
        session = new MediaSession(this, "pianobar offline");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { resumeOnFocus = false; pause(); }
            @Override public void onSkipToNext() { skip(1); }
            @Override public void onSkipToPrevious() { skip(-1); }
            @Override public void onStop() { stopSelf(); }
        });
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setActive(true);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("offline", "Offline music", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        startForeground(1, notification());
        String action = intent.getAction();
        if (STOP.equals(action)) stopSelf();
        else if (PLAY.equals(action)) load(intent.getStringExtra("id"));
        else if (NEXT.equals(action)) skip(1);
        else if (PREVIOUS.equals(action)) skip(-1);
        else if (TOGGLE.equals(action)) { resumeOnFocus = false; if (playing) pause(); else resume(); }
        return START_NOT_STICKY;
    }
    private void load(String id) {
        List<OfflineLibrary.Track> tracks = library.tracks();
        track = null;
        for (OfflineLibrary.Track item : tracks) if (item.id.equals(id)) { track = item; break; }
        if (track == null) { status = "That downloaded track is no longer available."; stopSelf(); return; }
        releasePlayer();
        currentId = track.id; currentTitle = track.title; status = "Loading downloaded track…";
        try {
            player = new MediaPlayer();
            player.setWakeMode(this, android.os.PowerManager.PARTIAL_WAKE_LOCK);
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setDataSource(library.file(id).getAbsolutePath());
            player.setOnPreparedListener(p -> { prepared = true; resume(); });
            player.setOnCompletionListener(p -> skip(1));
            player.setOnErrorListener((p, what, extra) -> {
                status = "This file could not be played."; stopSelf(); return true;
            });
            session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist).build());
            player.prepareAsync();
            update();
        } catch (Exception error) { status = "Could not open this downloaded track."; stopSelf(); }
    }
    private void resume() {
        if (player == null || !prepared) return;
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            status = "Playback is waiting for audio focus."; update(); return;
        }
        player.start(); playing = true; status = "Playing from this device"; update();
    }
    private void pause() {
        if (player != null && prepared && playing) player.pause();
        playing = false; status = "Paused"; update();
    }
    private void skip(int delta) {
        List<OfflineLibrary.Track> tracks = library.tracks();
        if (tracks.isEmpty()) { stopSelf(); return; }
        int index = 0;
        for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).id.equals(currentId)) index = i;
        load(tracks.get((index + delta + tracks.size()) % tracks.size()).id);
    }
    private PendingIntent action(String action) {
        return PendingIntent.getService(this, action.hashCode(), new Intent(this, OfflinePlayerService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).setAction("offline"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "offline").setSmallIcon(R.drawable.ic_note)
                .setContentTitle(currentTitle.isEmpty() ? "pianobar" : currentTitle)
                .setContentText(track == null ? "Offline library" : track.artist)
                .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(playing)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", action(PREVIOUS)).build())
                .addAction(new Notification.Action.Builder(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Play", action(TOGGLE)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", action(NEXT)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", action(STOP)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2))
                .build();
    }
    private void update() {
        session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        player != null && prepared ? player.getCurrentPosition() : 0, playing ? 1 : 0).build());
        getSystemService(NotificationManager.class).notify(1, notification());
    }
    private void releasePlayer() {
        if (player != null) { player.release(); player = null; }
        prepared = false; playing = false; resumeOnFocus = false;
    }
    @Override public void onDestroy() {
        releasePlayer(); audio.abandonAudioFocusRequest(focus); session.release();
        currentId = ""; currentTitle = ""; playing = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
