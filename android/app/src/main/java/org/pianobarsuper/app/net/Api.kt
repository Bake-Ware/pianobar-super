package org.pianobarsuper.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The server (or Cloudflare Access in front of it) wants a fresh sign-in. */
class LoginRequired : IOException("Sign in to your server.")

/** The server answered with an error message meant for the listener. */
class ApiError(val status: Int, message: String) : IOException(message)

/** Where sign-in cookies live; the WebView's CookieManager in the app, a map in tests. */
interface CookieStore {
    fun cookies(url: String): String?
    fun save(url: String, setCookie: String)
}

val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * pianobar's HTTP API. Credentials are scoped to one origin, redirects are
 * never followed (an identity provider redirect means "sign in again"), and
 * no Origin header is sent, which the server would compare to its Host.
 */
class Api(val origin: String, private val cookies: CookieStore, private val basic: () -> String?) {
    private val authInterceptor = Interceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()
        val builder = request.newBuilder()
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
        if (ServerAddress.sameOrigin(origin, url)) {
            cookies.cookies(url)?.let { builder.header("Cookie", it) }
            basic()?.let { builder.header("Authorization", it) }
        }
        val response = chain.proceed(builder.build())
        if (ServerAddress.sameOrigin(origin, url)) response.headers("Set-Cookie").forEach { cookies.save(url, it) }
        response
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .build()

    /** For slow calls such as the DJ building a playlist. */
    val slowClient: OkHttpClient = client.newBuilder().readTimeout(200, TimeUnit.SECONDS).build()

    /** The PCM stream: no read timeout beyond keepalive gaps. */
    val streamClient: OkHttpClient = client.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()

    fun url(path: String): String {
        require(path.startsWith("api/") && ".." !in path) { "Invalid API path" }
        return origin + path
    }

    fun request(path: String) = Request.Builder().url(url(path))

    suspend fun get(path: String, slow: Boolean = false): JsonElement = withContext(Dispatchers.IO) {
        call(request(path).get().build(), slow)
    }

    suspend fun post(path: String, body: JsonObject, slow: Boolean = false): JsonElement = withContext(Dispatchers.IO) {
        call(request(path).post(body.toString().toRequestBody(JSON)).build(), slow)
    }

    /** Raw bytes (artwork, voice samples). */
    suspend fun bytes(path: String, body: JsonObject? = null, limit: Int = 16 * 1024 * 1024): Pair<ByteArray, String?> =
        withContext(Dispatchers.IO) {
            val request = request(path).apply { if (body != null) post(body.toString().toRequestBody(JSON)) }.build()
            client.newCall(request).execute().use { response ->
                check(response)
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (bytes.size > limit) throw IOException("Response too large")
                bytes to response.header("Content-Type")
            }
        }

    private fun call(request: Request, slow: Boolean): JsonElement {
        (if (slow) slowClient else client).newCall(request).execute().use { response ->
            check(response)
            val type = response.header("Content-Type") ?: ""
            if (!type.startsWith("application/json")) throw LoginRequired()
            val text = response.body?.string() ?: ""
            if (text.length > 8 * 1024 * 1024) throw IOException("Response too large")
            return json.parseToJsonElement(text)
        }
    }

    companion object {
        const val USER_AGENT = "pianobar-native/2"
        val JSON = "application/json".toMediaType()

        @Throws(IOException::class)
        fun check(response: Response) {
            val code = response.code
            if (code == 401 || code in 300..399) throw LoginRequired()
            if (code == 403 && response.header("Content-Type")?.startsWith("application/json") != true) throw LoginRequired()
            if (code != 200) {
                val message = try {
                    response.peekBody(64 * 1024).string().let { json.parseToJsonElement(it).jsonObject["error"]?.jsonPrimitive?.contentOrNull }
                } catch (e: Exception) { null }
                throw ApiError(code, message ?: "The server returned HTTP $code.")
            }
        }
    }
}
