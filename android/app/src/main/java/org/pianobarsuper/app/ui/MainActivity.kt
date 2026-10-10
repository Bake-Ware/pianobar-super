package org.pianobarsuper.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.pianobarsuper.app.PianobarApp
import org.pianobarsuper.app.playback.Playback
import org.pianobarsuper.app.update.Updater

class MainActivity : ComponentActivity() {
    private var theme by mutableStateOf("system")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PianobarApp
        theme = app.repo.store.theme
        if (savedInstanceState == null) {
            if (intent?.action == Updater.ACTION_INSTALL) Updater.installNow() else Updater.checkInBackground()
        }
        if (savedInstanceState == null && app.repo.store.autoListen && app.repo.store.origin != null && !Playback.state.value.listening)
            Playback.listen(this)
        setContent {
            CompositionLocalProvider(LocalApp provides app) {
                PianobarTheme(theme) { AppRoot(onThemeChange = { theme = it; app.repo.store.theme = it }) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Updater.ACTION_INSTALL) Updater.installNow()
    }
}
