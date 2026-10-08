package org.pianobarsuper.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.pianobarsuper.app.net.ServerAddress

@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Text(buildAnnotatedString {
        append("pianobar")
        withStyle(SpanStyle(color = Palette.Orange)) { append(".") }
    }, style = MaterialTheme.typography.headlineLarge, modifier = modifier)
}

@Composable
fun SetupScreen() {
    val repo = repo()
    var address by remember { mutableStateOf(repo.store.server) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun connect() {
        val origin = try { ServerAddress.normalize(address) } catch (e: IllegalArgumentException) { error = e.message; return }
        error = null
        if (password.isNotEmpty()) repo.store.setPassword(password)
        repo.setServer(origin)
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Wordmark(Modifier.padding(top = 48.dp))
                Eyebrow("Your listening room")
                Text("Connect to your pianobar server to control your radio, listen on this phone and take songs offline.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(address, { address = it; error = null }, Modifier.fillMaxWidth().semantics { contentDescription = "Server address" },
                    label = { Text("Server address") }, placeholder = { Text("https://radio.example.com") }, singleLine = true,
                    isError = error != null, supportingText = { Text(error ?: "HTTPS over the Internet, or http:// for a server on your home network.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Web password (optional)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("Only if your server asks for one. Cloudflare Access and other sign-in pages open next.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { connect() }))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = ::connect, enabled = address.isNotBlank()) { Text("Connect") }
                }
            }
        }
    }
}
