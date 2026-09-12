package org.pianobarsuper.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.*;
import android.widget.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Web controls and login; all audio belongs to StreamPlayerService. No JavaScript bridge. */
public final class PlayerActivity extends Activity {
    private WebView web;
    private String origin, downloadPath;
    private TextView status, address;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            status.setText(StreamPlayerService.status);
            handler.postDelayed(this, 500);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try { origin = ServerAddress.normalize(getSharedPreferences("connection", MODE_PRIVATE).getString("server", "")); }
        catch (IllegalArgumentException e) { finish(); return; }
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 26, 26));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
        });
        LinearLayout controls = new LinearLayout(this);
        addButton(controls, "Listen", () -> start(StreamPlayerService.PLAY));
        addButton(controls, "Pause", () -> { if (StreamPlayerService.active) start(StreamPlayerService.PAUSE); });
        addButton(controls, "Stop", () -> stopService(new Intent(this, StreamPlayerService.class)));
        addButton(controls, "Back", this::finish);
        root.addView(controls);
        status = new TextView(this); status.setTextColor(Color.WHITE); root.addView(status);
        address = new TextView(this); address.setTextColor(Color.LTGRAY); address.setText(origin); root.addView(address);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.getSettings().setMediaPlaybackRequiresUserGesture(true);
        web.getSettings().setUserAgentString(web.getSettings().getUserAgentString() + " PianobarNative/1");
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Permit HTTPS identity-provider redirects; never send our native headers to them.
                return !NativeConnection.sameOrigin(origin, url) && !"https".equals(request.getUrl().getScheme());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Even old servers cannot open a competing Web Audio stream.
                if (NativeConnection.sameOrigin(origin, request.getUrl().toString()) && "/api/audio".equals(request.getUrl().getPath()))
                    return new WebResourceResponse("text/plain", "UTF-8", 409, "Native playback", Collections.emptyMap(),
                            new ByteArrayInputStream("Use the native Listen button".getBytes(StandardCharsets.UTF_8)));
                return null;
            }
            @Override public void onPageFinished(WebView view, String url) {
                address.setText(url.split("[?#]", 2)[0]);
                if (!NativeConnection.sameOrigin(origin, url)) return;
                CookieManager.getInstance().flush();
                // Compatibility with existing servers. No secrets or native methods are exposed to script.
                view.evaluateJavascript("try { autoListen=false; autoListenPending=false; stopListening(); startListening=async function() {}; " +
                        "localStorage.setItem('pianobarAutoListen','false'); " +
                        "document.getElementById('listen-button').hidden=true; " +
                        "document.getElementById('audio-status').textContent='Audio uses the Android Listen / Pause controls above.'; " +
                        "} catch(e) {}", null);
            }
            @Override public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler auth, String host, String realm) {
                if (!NativeConnection.sameOrigin(origin, view.getUrl()) || !Uri.parse(origin).getHost().equalsIgnoreCase(host)) {
                    auth.cancel(); return;
                }
                LinearLayout fields = new LinearLayout(PlayerActivity.this); fields.setOrientation(LinearLayout.VERTICAL);
                EditText user = new EditText(PlayerActivity.this); user.setContentDescription("Web username"); user.setHint("Username"); user.setText("pianobar"); user.setSingleLine();
                EditText password = new EditText(PlayerActivity.this); password.setContentDescription("Web password"); password.setHint("Web password");
                password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                fields.addView(user); fields.addView(password);
                new AlertDialog.Builder(PlayerActivity.this).setTitle("Sign in to " + host).setView(fields)
                        .setNegativeButton("Cancel", (d, w) -> auth.cancel()).setOnCancelListener(d -> auth.cancel())
                        .setPositiveButton("Sign in", (d, w) -> {
                            String name = user.getText().toString(), secret = password.getText().toString();
                            NativeConnection.basic.put(origin, "Basic " + Base64.encodeToString(
                                    (name + ":" + secret).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
                            auth.proceed(name, secret);
                        }).show();
            }
        });
        web.setDownloadListener((url, agent, disposition, mime, length) -> {
            if (!NativeConnection.sameOrigin(origin, url) || !Uri.parse(url).getPath().startsWith("/api/download/")) return;
            downloadPath = Uri.parse(url).getPath().substring(1);
            Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime == null ? "audio/mp4" : mime)
                    .putExtra(Intent.EXTRA_TITLE, URLUtil.guessFileName(url, disposition, mime));
            try { startActivityForResult(save, 44); }
            catch (ActivityNotFoundException e) { Toast.makeText(this, "No file picker installed.", Toast.LENGTH_LONG).show(); }

        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        if (saved != null) downloadPath = saved.getString("downloadPath");
        if (saved == null || web.restoreState(saved) == null) web.loadUrl(origin);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 44 || result != RESULT_OK || data == null || data.getData() == null || downloadPath == null) return;
        String path = downloadPath; downloadPath = null;
        Uri target = data.getData(); Context app = getApplicationContext();
        Toast.makeText(this, "Saving track…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            java.net.HttpURLConnection connection = null;
            String message = "Saved. Import the file from Downloads to listen offline.";
            try {
                connection = new NativeConnection(origin).open(path);
                NativeConnection.check(connection);
                String mime = connection.getContentType();
                if (mime == null || !mime.startsWith("audio/")) throw new java.io.IOException("Not audio");
                try (java.io.InputStream in = connection.getInputStream();
                     java.io.OutputStream out = app.getContentResolver().openOutputStream(target, "wt")) {
                    if (out == null) throw new java.io.IOException("Cannot save");
                    byte[] bytes = new byte[65536]; int n; long total = 0;
                    while ((n = in.read(bytes)) != -1) {
                        total += n; if (total > 100L * 1024 * 1024) throw new java.io.IOException("Too large");
                        out.write(bytes, 0, n);
                    }
                }
            } catch (Exception e) {
                message = "Download failed. Sign in and try again (100 MB maximum).";
                try { android.provider.DocumentsContract.deleteDocument(app.getContentResolver(), target); } catch (Exception ignored) {}
            } finally { if (connection != null) connection.disconnect(); }
            String notice = message;
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app, notice, Toast.LENGTH_LONG).show());
        }, "pianobar-download").start();
    }
    private void addButton(LinearLayout row, String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false);
        b.setOnClickListener(v -> action.run()); row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }
    private void start(String action) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 43);
        startForegroundService(new Intent(this, StreamPlayerService.class).setAction(action));
    }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); if (web != null) web.onPause(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) { web.saveState(out); out.putString("downloadPath", downloadPath); super.onSaveInstanceState(out); }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() { handler.removeCallbacks(refresh); if (web != null) web.destroy(); super.onDestroy(); }
}
