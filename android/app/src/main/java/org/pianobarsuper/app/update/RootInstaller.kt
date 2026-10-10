package org.pianobarsuper.app.update

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Silent installs on rooted devices. A normal `su` (Magisk and friends) is
 * tried first. Bakecar's head unit has no `su`: its firmware runs commands as
 * root through `/data/linux-tools/rsu 'command'`, which serializes jobs and
 * returns the command's output and exit code.
 */
object RootInstaller {
    private const val TAG = "pianobar-update"
    const val RSU = "/data/linux-tools/rsu"
    private val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system_ext/bin/su", "/debug_ramdisk/su")

    private fun su(): String? = SU_PATHS.firstOrNull { File(it).exists() }
    private fun rsu(): Boolean = File(RSU).exists()

    /** A root helper exists; whether it grants root is only known once it runs. */
    fun available(): Boolean = su() != null || rsu()

    /**
     * Runs `pm install -r` as root. Returns null on success (the system then
     * replaces this app, so the process is usually gone before this returns),
     * or what went wrong with each helper.
     */
    fun install(apk: File, packageName: String): String? {
        val command = UpdatePolicy.rootInstallCommand(apk, packageName)
        val attempts = buildList {
            su()?.let { add("su" to listOf(it, "-c", command)) }
            if (rsu()) add("rsu" to listOf("/system/bin/sh", RSU, "-t", "240", command))
        }
        if (attempts.isEmpty()) return "No root helper on this device."
        val failures = mutableListOf<String>()
        for ((name, argv) in attempts) {
            val output = execute(argv)
            if (output == null) { failures += "$name timed out"; continue }
            if (UpdatePolicy.rootInstallSucceeded(output)) return null
            Log.w(TAG, "$name install failed: ${output.take(400)}")
            failures += "$name: " + output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty().ifEmpty { "no output" }
        }
        return failures.joinToString("; ")
    }

    private fun execute(argv: List<String>): String? = try {
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = StringBuilder()
        val reader = Thread {
            try { process.inputStream.bufferedReader().use { output.append(it.readText()) } } catch (e: Exception) { /* Process ended. */ }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(300, TimeUnit.SECONDS)) { process.destroy(); null }
        else { reader.join(2000); output.toString() }
    } catch (e: Exception) {
        e.message ?: "could not start"
    }
}
