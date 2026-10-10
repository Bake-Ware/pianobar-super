package org.pianobarsuper.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat

/** PackageInstaller's answer for an update session. Success replaces this app, so there is nothing to do then. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i("pianobar-update", "install status $status ${message.orEmpty()}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let { Updater.onConfirmationNeeded(context, it) }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.onInstallFailed("The update was cancelled. Install it from More when you’re ready.")
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                Updater.onInstallFailed("This app can’t be updated in place: ${message ?: "it conflicts with the installed version"}.")
            else -> Updater.onInstallFailed(message ?: "Android would not install the update.")
        }
    }
}
