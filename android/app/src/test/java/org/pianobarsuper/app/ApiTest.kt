package org.pianobarsuper.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.pianobarsuper.app.net.Api
import org.pianobarsuper.app.net.ApiError
import org.pianobarsuper.app.net.CookieStore
import org.pianobarsuper.app.net.LoginRequired

class ApiTest {
    private val server = MockWebServer()
    private val jar = mutableMapOf<String, String>()
    private val cookies = object : CookieStore {
        override fun cookies(url: String) = jar.values.joinToString("; ").ifEmpty { null }
        override fun save(url: String, setCookie: String) { jar[setCookie.substringBefore("=")] = setCookie.substringBefore(";") }
    }
    private lateinit var api: Api

    @Before fun start() {
        server.start()
        api = Api("http://localhost:${server.port}/", cookies) { "Basic cGlhbm9iYXI6c2VjcmV0" }
    }

    @After fun stop() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun sendsSessionToItsOriginOnlyAndKeepsNewCookies() = runBlocking {
        jar["CF_Authorization"] = "CF_Authorization=token"
        server.enqueue(json("""{"ok":true}""").addHeader("Set-Cookie", "CF_AppSession=abc; Path=/; HttpOnly"))
        val reply = api.post("api/command", buildJsonObject { put("action", "act_songnext") })
        assertEquals("true", reply.jsonObject["ok"]!!.jsonPrimitive.content)
        val request = server.takeRequest()
        assertEquals("/api/command", request.path)
        assertEquals("Basic cGlhbm9iYXI6c2VjcmV0", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Cookie")!!.contains("CF_Authorization=token"))
        assertNull("No Origin header: the server compares it to Host", request.getHeader("Origin"))
        assertEquals("""{"action":"act_songnext"}""", request.body.readUtf8())
        assertEquals("CF_AppSession=abc", jar["CF_AppSession"])
    }

    @Test fun redirectsAndHtmlMeanSignInAgain() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://team.cloudflareaccess.com/login"))
        assertThrows(LoginRequired::class.java) { runBlocking { api.get("api/state") } }
        assertEquals(1, server.requestCount) // the redirect is never followed
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>login</html>"))
        assertThrows(LoginRequired::class.java) { runBlocking { api.get("api/state") } }
        server.enqueue(json("""{"error":"Sign in to pianobar."}""", 401))
        assertThrows(LoginRequired::class.java) { runBlocking { api.get("api/state") } }
    }

    @Test fun serverErrorsCarryTheirMessage() {
        server.enqueue(json("""{"error":"That station is unavailable."}""", 400))
        val error = assertThrows(ApiError::class.java) { runBlocking { api.post("api/command", buildJsonObject { put("selectStation", "x") }) } }
        assertEquals("That station is unavailable.", error.message)
        assertEquals(400, error.status)
    }

    @Test fun onlyApiPaths() {
        assertThrows(IllegalArgumentException::class.java) { api.url("../etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) { api.url("api/../../x") }
    }
}
