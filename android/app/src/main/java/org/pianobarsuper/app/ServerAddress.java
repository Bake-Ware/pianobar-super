package org.pianobarsuper.app;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** A server is an origin, not a login URL or a URL containing credentials. */
final class ServerAddress {
    static String normalize(String input) {
        try {
            URI uri = new URI(input.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (host == null || host.isEmpty() || uri.getRawUserInfo() != null || uri.getRawQuery() != null ||
                    uri.getRawFragment() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/")) ||
                    uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IllegalArgumentException("Enter the server address only, without a path, password, or query.");
            if (!scheme.equals("https") && !(scheme.equals("http") && isLocal(host)))
                throw new IllegalArgumentException("Use https://, or http:// for a local network server.");
            return new URI(scheme, null, host.toLowerCase(Locale.ROOT), uri.getPort(), "/", null, null).toASCIIString();
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Enter a valid server URL, such as https://radio.example.com.");
        }
    }
    private static boolean isLocal(String host) {
        host = host.toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.equals("[::1]") || host.equals("::1") || host.endsWith(".local") || host.endsWith(".lan")) return true;
        if (host.startsWith("[fc") || host.startsWith("[fd") || host.startsWith("[fe80:")) return true;
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        try {
            for (int i = 0; i < 4; ++i) {
                if (!parts[i].matches("[0-9]{1,3}")) return false;
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] > 255) return false;
            }
        } catch (NumberFormatException error) { return false; }
        return octets[0] == 10 || octets[0] == 127 || (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31);
    }
}
