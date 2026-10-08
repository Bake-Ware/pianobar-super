package org.pianobarsuper.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.pianobarsuper.app.PianobarApp
import org.pianobarsuper.app.net.ServerAddress

/**
 * Sign in to the server in a browser view: Cloudflare Access (or another
 * identity page) sets cookies the app then uses, and a pianobar web password
 * is kept encrypted. Finishes as soon as the API answers.
 */
class SignInActivity : ComponentActivity() {
    private var loading by mutableStateOf(true)
    private var address by mutableStateOf("")
    private var auth by mutableStateOf<HttpAuthHandler?>(null)
    private var password by mutableStateOf("")
    private var web: WebView? = null

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PianobarApp
        val origin = app.repo.store.origin ?: run { finish(); return }
        setContent {
            PianobarTheme(app.repo.store.theme) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding()) {
                        TopAppBar(
                            title = { Column { Text("Sign in"); Text(address.ifEmpty { origin }.toUri().host ?: "", style = androidx.compose.material3.MaterialTheme.typography.bodySmall) } },
                            navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Cancel") } },
                        )
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        AndroidView(factory = { context ->
                            WebView(context).apply {
                                web = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        val url = request.url.toString()
                                        // Identity providers must be HTTPS; nothing else leaves the server's origin.
                                        return !ServerAddress.sameOrigin(origin, url) && request.url.scheme != "https"
                                    }
                                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { loading = true; address = url }
                                    override fun onPageFinished(view: WebView, url: String) {
                                        loading = false
                                        address = url
                                        if (ServerAddress.sameOrigin(origin, url)) { CookieManager.getInstance().flush(); check() }
                                    }
                                    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                                        if (!ServerAddress.sameOrigin(origin, view.url ?: origin) || !host.equals(origin.toUri().host, true)) {
                                            handler.cancel(); return
                                        }
                                        password = ""
                                        auth = handler
                                    }
                                }
                                loadUrl(origin)
                            }
                        }, modifier = Modifier.fillMaxSize())
                    }
                    auth?.let { handler ->
                        AlertDialog(
                            onDismissRequest = { handler.cancel(); auth = null },
                            title = { Text("Web password") },
                            text = {
                                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                                    visualTransformation = PasswordVisualTransformation())
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    app.repo.store.setPassword(password)
                                    handler.proceed("pianobar", password)
                                    auth = null
                                }) { Text("Sign in") }
                            },
                            dismissButton = { TextButton(onClick = { handler.cancel(); auth = null }) { Text("Cancel") } },
                        )
                    }
                }
            }
        }
    }

    /** Finish once the API accepts the session the page established. */
    private fun check() {
        val app = application as PianobarApp
        app.repo.reconnect()
        lifecycleScope.launch {
            val api = app.repo.api.value ?: return@launch
            try {
                api.get("api/state")
                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) { /* Still signing in; stay on the page. */ }
        }
    }

    override fun onDestroy() {
        web?.destroy()
        super.onDestroy()
    }
}
