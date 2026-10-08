package org.pianobarsuper.app.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import androidx.core.content.edit
import org.pianobarsuper.app.net.CookieStore
import org.pianobarsuper.app.net.ServerAddress
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The chosen server and how to sign in to it. A web password (HTTP Basic) is
 * encrypted with a key that never leaves the Android Keystore; Cloudflare
 * Access and other sign-in cookies live in the WebView cookie jar.
 */
class ServerStore(context: Context) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)

    var server: String
        get() = prefs.getString("server", "") ?: ""
        set(value) { prefs.edit { putString("server", value) } }

    val origin: String?
        get() = try { ServerAddress.normalize(server) } catch (e: IllegalArgumentException) { null }

    var deviceName: String
        get() = prefs.getString("device_name", null) ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".take(80)
        set(value) { prefs.edit { putString("device_name", value.trim().take(80)) } }

    /** Start listening on this phone whenever the app connects. */
    var autoListen: Boolean
        get() = prefs.getBoolean("auto_listen", false)
        set(value) { prefs.edit { putBoolean("auto_listen", value) } }

    /** "system", "dark" or "light". */
    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(value) { prefs.edit { putString("theme", value) } }

    fun basicAuthorization(): String? {
        val sealed = prefs.getString("basic", null) ?: return null
        return try {
            val (iv, data) = sealed.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun setPassword(password: String?) {
        if (password.isNullOrEmpty()) { prefs.edit { remove("basic") }; return }
        val header = "Basic " + Base64.encodeToString("pianobar:$password".toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(header.toByteArray(StandardCharsets.UTF_8))
        prefs.edit { putString("basic", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)) }
    }

    fun signOut() {
        setPassword(null)
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    private companion object { const val KEY = "pianobar-web-password" }
}

/** Cookies shared with the sign-in WebView, so a Cloudflare Access login covers API calls. */
object WebViewCookies : CookieStore {
    override fun cookies(url: String): String? = CookieManager.getInstance().getCookie(url)
    override fun save(url: String, setCookie: String) = CookieManager.getInstance().setCookie(url, setCookie)
}
