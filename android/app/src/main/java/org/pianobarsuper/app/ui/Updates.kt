package org.pianobarsuper.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pianobarsuper.app.BuildConfig
import org.pianobarsuper.app.update.UpdateStatus
import org.pianobarsuper.app.update.Updater

/** More → This app: the version, Check for updates, and the permission updates need. */
@Composable
fun UpdatesSection() {
    val context = LocalContext.current
    val status by Updater.status.collectAsStateWithLifecycle()
    var needsPermission by remember { mutableStateOf(Updater.needsInstallPermission(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { needsPermission = Updater.needsInstallPermission(context) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("App updates", style = MaterialTheme.typography.bodyLarge)
        Text(describe(status), style = MaterialTheme.typography.bodySmall,
            color = if (status is UpdateStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        (status as? UpdateStatus.Downloading)?.let { LinearProgressIndicator(progress = { it.fraction }, Modifier.fillMaxWidth()) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val busy = status is UpdateStatus.Checking || status is UpdateStatus.Downloading || status is UpdateStatus.Installing
            if (status is UpdateStatus.Ready) Button(onClick = { Updater.installNow() }) { Text("Install now") }
            OutlinedButton(onClick = { Updater.checkInBackground(userAsked = true) }, enabled = !busy) { Text("Check for updates") }
        }
        if (needsPermission) {
            Text("To install updates, allow pianobar to install apps from your server.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { context.startActivity(Updater.installPermissionIntent(context)) }) { Text("Allow updates") }
        }
    }
}

private fun describe(status: UpdateStatus) = when (status) {
    UpdateStatus.Idle -> "Version ${BuildConfig.VERSION_NAME}. Updates come from your server and install when you aren’t listening."
    UpdateStatus.Checking -> "Checking your server…"
    is UpdateStatus.UpToDate -> status.note.ifEmpty { "Version ${BuildConfig.VERSION_NAME} is the latest." }
    is UpdateStatus.Downloading -> "Downloading version ${status.apk.versionName}…"
    is UpdateStatus.Ready -> "Version ${status.apk.versionName} is ready. It installs when you stop listening."
    is UpdateStatus.Installing -> "Installing version ${status.apk.versionName}…"
    is UpdateStatus.Confirm -> "Confirm the update to version ${status.apk.versionName} when Android asks."
    is UpdateStatus.Failed -> status.message
}

/** Once, when the first update is found: explain the "install unknown apps" switch. */
@Composable
fun UpdatePermissionGuide() {
    val context = LocalContext.current
    val status by Updater.status.collectAsStateWithLifecycle()
    var dismissed by remember { mutableStateOf(Updater.guideShown) }
    val found = status is UpdateStatus.Ready || status is UpdateStatus.Downloading
    if (dismissed || !found || !Updater.needsInstallPermission(context)) return
    val close = { dismissed = true; Updater.guideShown = true }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Allow app updates") },
        text = { Text("A new pianobar app is on your server. Android asks once before an app can install updates: turn on “Allow from this source” for pianobar, then come back.") },
        confirmButton = { TextButton(onClick = { close(); context.startActivity(Updater.installPermissionIntent(context)) }) { Text("Open settings") } },
        dismissButton = { TextButton(onClick = close) { Text("Not now") } },
    )
}
