package org.pianobarsuper.app.net

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** A server is an origin, not a login URL or a URL containing credentials. */
object ServerAddress {
    fun normalize(input: String): String {
        try {
            val uri = URI(input.trim())
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: ""
            val host = uri.host
            if (host.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") || uri.port == 0 || uri.port > 65535
            ) throw IllegalArgumentException("Enter the server address only, without a path, password, or query.")
            if (scheme != "https" && !(scheme == "http" && isLocal(host)))
                throw IllegalArgumentException("Use https://, or http:// for a local network server.")
            return URI(scheme, null, host.lowercase(Locale.ROOT), uri.port, "/", null, null).toASCIIString()
        } catch (error: URISyntaxException) {
            throw IllegalArgumentException("Enter a valid server URL, such as https://radio.example.com.")
        }
    }

    fun sameOrigin(origin: String, url: String): Boolean = try {
        val a = URI(origin)
        val b = URI(url)
        a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && port(a) == port(b) && b.rawUserInfo == null
    } catch (e: Exception) {
        false
    }

    private fun port(uri: URI) = if (uri.port == -1) (if (uri.scheme.equals("https", true)) 443 else 80) else uri.port

    private fun isLocal(hostName: String): Boolean {
        val host = hostName.lowercase(Locale.ROOT)
        if (host == "localhost" || host == "[::1]" || host == "::1" || host.endsWith(".local") || host.endsWith(".lan")) return true
        if (host.startsWith("[fc") || host.startsWith("[fd") || host.startsWith("[fe80:")) return true
        val parts = host.split(".")
        if (parts.size != 4 || parts.any { !it.matches(Regex("[0-9]{1,3}")) }) return false
        val octets = parts.map { it.toInt() }
        if (octets.any { it > 255 }) return false
        return octets[0] == 10 || octets[0] == 127 || (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31)
    }
}
