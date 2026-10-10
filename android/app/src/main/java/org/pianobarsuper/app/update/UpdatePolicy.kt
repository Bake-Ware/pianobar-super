package org.pianobarsuper.app.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** GET /api/android/version: the APK the server publishes at /pianobar.apk. */
@Serializable
data class ServerApk(
    @SerialName("package") val packageName: String = "",
    val versionCode: Long = 0,
    val versionName: String = "",
    val sha256: String = "",
    val size: Long = 0,
)

/** The rules an update must pass, kept free of Android so they can be unit tested. */
object UpdatePolicy {
    const val MAX_SIZE = 200L * 1024 * 1024
    private val HEX64 = Regex("[0-9a-f]{64}")

    /** The server offers a newer build of this very app, with a digest to check it against. */
    fun isNewer(apk: ServerApk, packageName: String, installedCode: Long): Boolean =
        apk.packageName == packageName && apk.versionCode > installedCode &&
            HEX64.matches(apk.sha256) && apk.size in 1..MAX_SIZE

    /**
     * An install nobody asked for waits until it can't interrupt anyone: nothing plays on this
     * phone and no pianobar screen is showing (a root install closes the app, and anything else
     * may pop up a confirmation), and it is tried once per build.
     */
    fun mayInstallUnattended(playing: Boolean, appVisible: Boolean, alreadyTried: Boolean): Boolean =
        !playing && !appVisible && !alreadyTried

    /** Updates can be large: an unattended download waits for Wi-Fi or another unmetered network. */
    fun mayDownloadUnattended(metered: Boolean): Boolean = !metered

    /** Only an APK signed by exactly the certificates of the installed app may replace it. */
    fun sameSigners(installed: Collection<String>, candidate: Collection<String>): Boolean =
        installed.isNotEmpty() && installed.toSet() == candidate.toSet()

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().hex()
    }

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    /** One argument for `sh -c`, whatever characters the path holds. */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Reinstall over this app and keep it as its own installer of record, so later updates can be silent. */
    fun rootInstallCommand(apk: File, packageName: String): String =
        "pm install -r -i ${shellQuote(packageName)} ${shellQuote(apk.absolutePath)}"

    /** `pm install` prints "Success" on its own line; anything else is a failure. */
    fun rootInstallSucceeded(output: String): Boolean = output.lineSequence().any { it.trim() == "Success" }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
