package org.pianobarsuper.app;

import android.webkit.CookieManager;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/** Credentials are origin-scoped. Never follow a redirect carrying session headers. */
final class NativeConnection {
    static final ConcurrentHashMap<String, String> basic = new ConcurrentHashMap<>();
    final String origin;
    NativeConnection(String origin) { this.origin = ServerAddress.normalize(origin); }
    static boolean sameOrigin(String origin, String url) {
        try {
            URI a = URI.create(origin), b = URI.create(url);
            return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost()) &&
                    port(a) == port(b) && b.getRawUserInfo() == null;
        } catch (Exception e) { return false; }
    }
    private static int port(URI uri) { return uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort(); }
    HttpURLConnection open(String path) throws IOException {
        if (!path.startsWith("api/") || path.contains("..")) throw new IOException("Invalid API path");
        HttpURLConnection c = (HttpURLConnection)new URL(origin + path).openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(10000); c.setReadTimeout(10000);
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("User-Agent", "pianobar-native/" + BuildConfig.VERSION_NAME);
        String cookies = CookieManager.getInstance().getCookie(origin);
        if (cookies != null) c.setRequestProperty("Cookie", cookies);
        String auth = basic.get(origin);
        if (auth != null) c.setRequestProperty("Authorization", auth);
        return c;
    }
    static final class LoginRequired extends IOException {}
    static void check(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code == 401 || code == 403 || code >= 300 && code < 400) throw new LoginRequired();
        if (code != 200) throw new IOException("Server returned HTTP " + code);
    }
    JSONObject json(String path, JSONObject body) throws Exception {
        HttpURLConnection c = open(path);
        try {
            if (body != null) {
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            }
            check(c);
            String type = c.getContentType();
            if (type == null || !type.startsWith("application/json")) throw new LoginRequired();
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[8192]; int n;
                while ((n = in.read(bytes)) != -1) {
                    if (out.size() + n > 2 * 1024 * 1024) throw new IOException("Response too large");
                    out.write(bytes, 0, n);
                }
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally { c.disconnect(); }
    }
}
