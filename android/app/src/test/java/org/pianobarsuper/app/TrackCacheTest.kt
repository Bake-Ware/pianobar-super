package org.pianobarsuper.app

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.pianobarsuper.app.net.Api
import org.pianobarsuper.app.net.CookieStore
import org.pianobarsuper.app.playback.TrackCache
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TrackCacheTest {
    private val server = MockWebServer()
    private val cookies = object : CookieStore {
        override fun cookies(url: String): String? = null
        override fun save(url: String, setCookie: String) {}
    }
    private lateinit var dir: File
    private lateinit var cache: TrackCache
    private val ready = LinkedBlockingQueue<String>()
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val songs = mutableMapOf<String, ByteArray>()
    /** Per id: answers to give before the real file (status codes). */
    private val before = mutableMapOf<String, MutableList<Int>>()

    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val id = request.path!!.removePrefix("/api/tracks/")
                val data = songs[id] ?: return MockResponse().setResponseCode(404)
                before[id]?.removeFirstOrNull()?.let { code ->
                    return if (code == 200) MockResponse().setHeader("Content-Type", "text/html").setBody("<html>sign in</html>")
                    else MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody("{}")
                }
                val range = request.getHeader("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
                val body = Buffer().write(if (range != null) data.copyOfRange(range, data.size) else data)
                return MockResponse().setResponseCode(if (range != null) 206 else 200).setHeader("Content-Type", "audio/mpeg")
                    .apply { if (range != null) setHeader("Content-Range", "bytes $range-${data.size - 1}/${data.size}") }
                    .setBody(body)
            }
        }
        server.start()
        dir = Files.createTempDirectory("tracks").toFile()
        val api = Api("http://localhost:${server.port}/", cookies) { "Basic dGVzdA==" }
        cache = TrackCache(dir, api) { ready.add(it) }
        songs[a] = ByteArray(300_000) { (it % 251).toByte() }
        songs[b] = ByteArray(200_000) { (it % 13).toByte() }
        songs[c] = ByteArray(1000) { 7 }
    }

    @After fun stop() {
        cache.stop()
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun next() = ready.poll(10, TimeUnit.SECONDS)

    @Test fun downloadsTheCurrentSongFirstThenTheNext() {
        cache.want(listOf(a, b))
        assertEquals(a, next())
        assertEquals(b, next())
        assertArrayEquals(songs[a], cache.file(a).readBytes())
        assertArrayEquals(songs[b], cache.file(b).readBytes())
        assertEquals(listOf("/api/tracks/$a", "/api/tracks/$b"), requests.map { it.path })
        assertEquals("Basic dGVzdA==", requests[0].getHeader("Authorization"))
        // Already here: nothing is fetched again.
        cache.want(listOf(a, b))
        Thread.sleep(200)
        assertEquals(2, requests.size)
    }

    @Test fun resumesAPartialFileWithARange() {
        File(dir, "$a.part").writeBytes(songs[a]!!.copyOf(1000))
        cache.want(listOf(a))
        assertEquals(a, next())
        assertEquals("bytes=1000-", requests.single().getHeader("Range"))
        assertArrayEquals(songs[a], cache.file(a).readBytes())
        assertFalse(File(dir, "$a.part").exists())
    }

    @Test fun waitsWhileTheServerIsStillDownloadingAndRejectsSignInPages() {
        before[a] = mutableListOf(503, 200)
        cache.want(listOf(a))
        assertEquals(a, next())
        assertEquals(3, requests.size)
        assertArrayEquals(songs[a], cache.file(a).readBytes())
    }

    @Test fun keepsOnlyTheWantedSongsAndThePlayingOne() {
        cache.want(listOf(a, b))
        next(); next()
        cache.want(listOf(c), extra = listOf(b))
        assertEquals(c, next())
        Thread.sleep(100)
        assertFalse(cache.has(a))
        assertTrue(cache.has(b))
        assertTrue(cache.has(c))
        cache.want(listOf("not-an-id", "../$c"))
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }
}
