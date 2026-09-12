package org.pianobarsuper.app;

import android.content.*;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class StreamTest {
    private static void waitFor(java.util.function.BooleanSupplier condition) {
        long end = SystemClock.elapsedRealtime() + 15000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100);
        assertTrue("Timed out: " + StreamPlayerService.status, condition.getAsBoolean());
    }
    private static final class Server implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        final ExecutorService workers = Executors.newCachedThreadPool();
        final List<Socket> streams = new CopyOnWriteArrayList<>();
        final AtomicInteger heartbeats = new AtomicInteger(), registrations = new AtomicInteger(), skips = new AtomicInteger();
        volatile boolean closed, reject;
        final String origin = "http://127.0.0.1:" + server.getLocalPort() + "/";
        Server() throws Exception { workers.execute(() -> { while (!closed) try { Socket socket=server.accept(); workers.execute(() -> handle(socket)); } catch(Exception ignored) {} }); }
        void handle(Socket socket) {
            try (Socket s = socket) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String first = reader.readLine(); if (first == null) return;
                String path = first.split(" ")[1], line; int length = 0; boolean auth = false;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.split(":",2)[1].trim());
                    if (line.equals("Authorization: Basic fixture")) auth = true;
                }
                char[] body = new char[length]; int offset=0;
                while (offset<length) { int n=reader.read(body,offset,length-offset); if(n<0)break; offset+=n; }
                if (path.equals("/")) {
                    reply(s,200,"text/html","<html><body><h1>Test station controls</h1><script>fetch('/api/audio').then(r=>document.body.append('Browser audio '+r.status))</script></body></html>"); return;
                }
                if (reject || !auth) { reply(s,401,"application/json","{}"); return; }
                if (path.equals("/api/redirect")) {
                    s.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/stolen\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); return;
                }
                if (path.equals("/api/audio")) {
                    streams.add(s);
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    while (!closed) {
                        out.writeInt(882); out.writeInt(44100); out.writeInt(1); out.writeInt(0); out.writeInt(1);
                        for(int i=0;i<441;i++) out.writeShort(i%2==0 ? 2000 : -2000);
                        out.flush(); Thread.sleep(10);
                    }
                } else {
                    String response = "{}";
                    if (path.equals("/api/clients/register")) response = "{\"id\":\"native-" + registrations.incrementAndGet() + "\"}";
                    if (path.equals("/api/clients/heartbeat")) { heartbeats.incrementAndGet(); response="{\"enabled\":true,\"volume\":1}"; }
                    if (path.equals("/api/state")) response="{\"state\":{\"title\":\"Fixture music\",\"artist\":\"Synthetic audio\",\"output\":\"browser\"}}";
                    if (path.equals("/api/command") && new String(body).contains("act_songnext")) skips.incrementAndGet();
                    reply(s,200,"application/json",response);
                }
            } catch(Exception ignored) {} finally { streams.remove(socket); }
        }
        void reply(Socket socket,int code,String mime,String body) throws IOException {
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            socket.getOutputStream().write(("HTTP/1.1 "+code+" Result\r\nContent-Type: "+mime+"\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(bytes);
        }
        void drop() { for(Socket s: streams)try{s.close();}catch(Exception ignored){} }
        @Override public void close() throws Exception { closed=true; drop(); server.close(); workers.shutdownNow(); }
    }
    @Test public void playerControlsUseNativeAudioAndBlockWebAudio() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard");
        try(Server server=new Server()) {
            context.getSharedPreferences("connection",0).edit().putString("server",server.origin).commit();
            NativeConnection.basic.put(server.origin,"Basic fixture");
            try(ActivityScenario<PlayerActivity> activity=ActivityScenario.launch(PlayerActivity.class)) {
                assertTrue(device.wait(Until.hasObject(By.textContains("Test station controls")),10000));
                assertTrue(device.wait(Until.hasObject(By.textContains("Browser audio 409")),10000));
                device.findObject(By.text("Listen")).click();
                long before=StreamPlayerService.samplesWritten;
                waitFor(()->StreamPlayerService.samplesWritten>before+44100);
                context.stopService(new Intent(context,StreamPlayerService.class));
                waitFor(()->!StreamPlayerService.active);
            }
        } finally {
            context.stopService(new Intent(context,StreamPlayerService.class));
            NativeConnection.basic.clear(); context.getSharedPreferences("connection",0).edit().clear().commit();
        }
    }
    @Test public void streamsWithScreenOffRenewsLeaseReconnectsAndStops() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard");
        try(Server server=new Server()) {
            context.getSharedPreferences("connection",0).edit().putString("server",server.origin).putBoolean("auto_open",false).commit();
            NativeConnection.basic.put(server.origin,"Basic fixture");
            try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)) {
                NativeConnection connection=new NativeConnection(server.origin);
                assertThrows(NativeConnection.LoginRequired.class,()->connection.json("api/redirect",null));
                context.startForegroundService(new Intent(context,StreamPlayerService.class).setAction(StreamPlayerService.PLAY));
                long baseline=StreamPlayerService.samplesWritten;
                waitFor(()->StreamPlayerService.samplesWritten>baseline+44100);
                device.pressHome(); device.sleep();
                long before=StreamPlayerService.samplesWritten;
                // Exceeds the real server's 90-second browser lease, without an Activity heartbeat.
                SystemClock.sleep(95000);
                assertTrue(StreamPlayerService.active);
                assertTrue(StreamPlayerService.samplesWritten>before+44100*60L);
                assertTrue("Background lease renewal",server.heartbeats.get()>=5);
                device.wakeUp();
                context.startService(new Intent(context,StreamPlayerService.class).setAction(StreamPlayerService.PAUSE));
                waitFor(()->StreamPlayerService.paused);
                SystemClock.sleep(250);
                long pausedAt=StreamPlayerService.samplesWritten;
                SystemClock.sleep(1000); assertEquals(pausedAt,StreamPlayerService.samplesWritten);
                context.startService(new Intent(context,StreamPlayerService.class).setAction(StreamPlayerService.TOGGLE));
                waitFor(()->StreamPlayerService.samplesWritten>pausedAt+44100);
                int registered=server.registrations.get(); server.drop();
                waitFor(()->server.registrations.get()>registered);
                long reconnect=StreamPlayerService.samplesWritten;
                waitFor(()->StreamPlayerService.samplesWritten>reconnect+44100);
                context.startService(new Intent(context,StreamPlayerService.class).setAction(StreamPlayerService.NEXT));
                waitFor(()->server.skips.get()>0);
                server.reject=true; server.drop();
                waitFor(()->!StreamPlayerService.active);
                assertTrue(StreamPlayerService.status.startsWith("Session expired"));
            }
        } finally {
            device.wakeUp(); context.stopService(new Intent(context,StreamPlayerService.class));
            NativeConnection.basic.clear();
            context.getSharedPreferences("connection",0).edit().clear().commit();
        }
    }
}
