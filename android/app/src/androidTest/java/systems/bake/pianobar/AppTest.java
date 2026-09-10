package systems.bake.pianobar;
import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AppTest {
    @Test public void setupAndOfflinePlayback() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("connection", 0).edit().clear().commit();
        OfflineLibrary library = new OfflineLibrary(context);
        for (OfflineLibrary.Track track : library.tracks()) library.remove(track.id);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            assertTrue(device.wait(Until.hasObject(By.desc("Server address")), 5000));
            assertEquals("", context.getSharedPreferences("connection", 0).getString("server", ""));
            device.findObject(By.desc("Server address")).setText("http://10.0.2.2:8765");
            device.findObject(By.text("Save server")).click();
            assertTrue(device.wait(Until.hasObject(By.text("http://10.0.2.2:8765/")), 5000));
            assertEquals("http://10.0.2.2:8765/", context.getSharedPreferences("connection", 0).getString("server", ""));
            activity.recreate();
            assertTrue(device.wait(Until.hasObject(By.text("http://10.0.2.2:8765/")), 5000));
            Uri source = Uri.parse("content://systems.bake.pianobar.test.audio/test.wav");
            OfflineLibrary.Track track = library.importTrack(source);
            assertEquals("Offline test.wav", track.title);
            assertEquals(track.id, library.importTrack(source).id);
            assertEquals(1, library.tracks().size());
            // The original can disappear: playback must use the app's own copy.
            context.getContentResolver().delete(source, null, null);
            context.startForegroundService(new Intent(context, OfflinePlayerService.class).setAction(OfflinePlayerService.PLAY).putExtra("id", track.id));
            waitPlaying(true);
            device.pressHome();
            SystemClock.sleep(3000);
            assertTrue(OfflinePlayerService.playing);
            device.sleep(); SystemClock.sleep(2000); device.wakeUp();
            assertTrue(OfflinePlayerService.playing);
            context.startService(new Intent(context, OfflinePlayerService.class).setAction(OfflinePlayerService.TOGGLE));
            waitPlaying(false);
            context.startService(new Intent(context, OfflinePlayerService.class).setAction(OfflinePlayerService.TOGGLE));
            waitPlaying(true);
            context.stopService(new Intent(context, OfflinePlayerService.class));
            waitPlaying(false);
            library.remove(track.id);
            assertTrue(library.tracks().isEmpty());
            assertFalse(library.file(track.id).exists());
        } finally {
            context.stopService(new Intent(context, OfflinePlayerService.class));
            context.getSharedPreferences("connection", 0).edit().clear().commit();
        }
    }
    private static void waitPlaying(boolean playing) {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (OfflinePlayerService.playing != playing && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        assertEquals(playing, OfflinePlayerService.playing);
    }
}
