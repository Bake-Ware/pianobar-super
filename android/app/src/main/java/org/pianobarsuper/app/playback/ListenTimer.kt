package org.pianobarsuper.app.playback

import android.os.SystemClock
import android.util.Log

/**
 * Times each stage from a listen request (tap, widget, media key) to the
 * first audible audio, for `adb logcat -s pianobar.latency`.
 */
object ListenTimer {
    const val TAG = "pianobar.latency"
    @Volatile private var started = 0L
    @Volatile private var last = 0L
    @Volatile private var running = false

    /** The first request wins: a widget tap reaches the service as a second request. */
    fun begin(source: String) {
        val now = SystemClock.elapsedRealtime()
        if (running && now - started < 10_000) return
        started = now; last = now; running = true
        Log.i(TAG, "listen requested from $source")
    }

    fun mark(stage: String) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        Log.i(TAG, "+${now - started}ms $stage (${now - last}ms)")
        last = now
    }

    fun finish(stage: String) { mark(stage); running = false }

    fun cancel() { running = false }
}
