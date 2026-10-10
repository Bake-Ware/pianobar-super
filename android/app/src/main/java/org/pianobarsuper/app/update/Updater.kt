package org.pianobarsuper.app.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Request
import org.pianobarsuper.app.BuildConfig
import org.pianobarsuper.app.PianobarApp
import org.pianobarsuper.app.R
import org.pianobarsuper.app.net.Api
import org.pianobarsuper.app.net.ApiError
import org.pianobarsuper.app.net.LoginRequired
import org.pianobarsuper.app.net.json
import org.pianobarsuper.app.playback.Playback
import org.pianobarsuper.app.ui.MainActivity
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val note: String = "") : UpdateStatus
    data class Downloading(val apk: ServerApk, val fraction: Float) : UpdateStatus
    /** Verified and waiting: for the radio on this phone to stop, or for the user to tap Install. */
    data class Ready(val apk: ServerApk) : UpdateStatus
    data class Installing(val apk: ServerApk) : UpdateStatus
    /** Android wants the user to confirm; a prompt or notification is showing. */
    data class Confirm(val apk: ServerApk) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

/**
 * Updates the app from its own server. The server publishes one APK at
 * /pianobar.apk and describes it at /api/android/version. A newer build is
 * downloaded to the app's cache, checked against the published sha256 and the
 * installed app's signing certificates, and installed: silently as root where
 * possible (Bakecar), otherwise through PackageInstaller, unattended when
 * Android allows a self-update and with a confirmation prompt when it doesn't.
 * The install waits while this phone is playing unless the user taps Install.
 */
object Updater {
    private const val TAG = "pianobar-update"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION = 7
    const val ACTION_INSTALL = "org.pianobarsuper.app.update.INSTALL"

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()
    private val lock = Mutex()
    private lateinit var app: PianobarApp
    private var ready: Pair<ServerApk, File>? = null
    /** Unattended installs are tried once per build; after that the user taps Install. */
    private var autoTried = -1L

    fun init(app: PianobarApp) {
        this.app = app
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        WorkManager.getInstance(app).enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build())
        // A deferred update goes in as soon as nothing plays on this phone.
        app.repo.scope.launch {
            Playback.state.collect { if (!playing() && _status.value is UpdateStatus.Ready) install(userAsked = false) }
        }
    }

    private fun playing() = Playback.state.value.let { it.listening || it.localPlaying }

    /** Installs from settings need either root or the "install unknown apps" permission. */
    fun needsInstallPermission(context: Context): Boolean =
        !context.packageManager.canRequestPackageInstalls() && !RootInstaller.available()

    fun installPermissionIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

    private val prefs get() = app.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** Shown once: why "install unknown apps" matters for updates. */
    var guideShown: Boolean
        get() = prefs.getBoolean("guide_shown", false)
        set(value) { prefs.edit { putBoolean("guide_shown", value) } }

    fun checkInBackground(userAsked: Boolean = false) { app.repo.scope.launch { check(userAsked) } }

    /** Ask the server, download a newer build, then install it now or once nothing plays. */
    suspend fun check(userAsked: Boolean) {
        val apk = (if (lock.tryLock()) try { fetch(userAsked) } finally { lock.unlock() } else null) ?: return
        if (userAsked || !playing()) install(userAsked) else notifyReady(apk)
    }

    /** The verified newer build, now in [ready]; null when there is nothing to install. */
    private suspend fun fetch(userAsked: Boolean): ServerApk? {
        if (_status.value is UpdateStatus.Installing || _status.value is UpdateStatus.Confirm) return null
        val api = app.repo.api.value ?: run { if (userAsked) _status.value = UpdateStatus.Failed("Choose your server first."); return null }
        if (userAsked || _status.value !is UpdateStatus.Ready) _status.value = UpdateStatus.Checking
        val apk = try {
            json.decodeFromJsonElement<ServerApk>(api.get("api/android/version"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _status.value = if (e.status == 404) UpdateStatus.UpToDate("Your server has no app to offer.") else UpdateStatus.Failed(e.message ?: "Could not check for updates.")
            return null
        } catch (e: LoginRequired) {
            _status.value = UpdateStatus.Failed("Sign in to your server to check for updates.")
            return null
        } catch (e: Exception) {
            _status.value = if (userAsked) UpdateStatus.Failed(e.message ?: "Could not check for updates.") else UpdateStatus.Idle
            return null
        }
        if (!UpdatePolicy.isNewer(apk, app.packageName, BuildConfig.VERSION_CODE.toLong())) {
            _status.value = UpdateStatus.UpToDate()
            ready = null
            return null
        }
        val file = ready?.takeIf { it.first == apk && it.second.isFile }?.second ?: try {
            download(api, apk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "update download failed", e)
            _status.value = UpdateStatus.Failed(if (e is LoginRequired) "Sign in to your server to download the update." else e.message ?: "The download failed.")
            return null
        }
        val problem = withContext(Dispatchers.IO) { verify(file, apk) }
        if (problem != null) {
            file.delete()
            ready = null
            _status.value = UpdateStatus.Failed(problem)
            return null
        }
        ready = apk to file
        _status.value = UpdateStatus.Ready(apk)
        return apk
    }

    private suspend fun download(api: Api, apk: ServerApk): File = withContext(Dispatchers.IO) {
        val folder = File(app.cacheDir, "updates").apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }
        val target = File(folder, "pianobar-${apk.versionCode}.apk")
        val partial = File(folder, target.name + ".part")
        _status.value = UpdateStatus.Downloading(apk, 0f)
        val request = Request.Builder().url(api.origin + "pianobar.apk").build()
        api.slowClient.newCall(request).execute().use { response ->
            Api.check(response)
            if (response.header("Content-Type")?.startsWith("text/html") == true) throw LoginRequired()
            val body = response.body ?: throw IOException("The server sent no app.")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > UpdatePolicy.MAX_SIZE) throw IOException("The app download is too large.")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        _status.value = UpdateStatus.Downloading(apk, (total.toFloat() / apk.size).coerceAtMost(1f))
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha != apk.sha256) { partial.delete(); throw IOException("The download doesn’t match the server’s checksum.") }
        }
        if (!partial.renameTo(target)) throw IOException("Could not save the update.")
        target
    }

    /** null when the file is the advertised build of this app, signed by the same certificates. */
    private fun verify(file: File, apk: ServerApk): String? {
        if (UpdatePolicy.sha256(file) != apk.sha256) return "The update doesn’t match the server’s checksum."
        val pm = app.packageManager
        val archive = archiveInfo(pm, file) ?: return "The update isn’t a valid app."
        if (archive.packageName != app.packageName) return "The update is for a different app."
        if (versionCode(archive) != apk.versionCode) return "The update’s version doesn’t match the server."
        val installed = signers(pm.getPackageInfo(app.packageName, signatureFlag()))
        if (!UpdatePolicy.sameSigners(installed, signers(archive))) {
            Log.w(TAG, "refusing update signed by ${signers(archive)}; installed is $installed")
            return "The update is signed by someone else, so it was not installed."
        }
        return null
    }

    private fun signatureFlag() =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? =
        pm.getPackageArchiveInfo(file.absolutePath, signatureFlag())

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): List<String> {
        val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else null
        return (certificates ?: info.signatures ?: emptyArray()).map { UpdatePolicy.sha256(it.toByteArray()) }
    }

    /** From the Install button or notification: install now, even while playing. */
    fun installNow() { app.repo.scope.launch { if (ready == null) check(userAsked = true) else install(userAsked = true) } }

    private suspend fun install(userAsked: Boolean) {
        lock.withLock {
            val (apk, file) = ready ?: return
            if (_status.value !is UpdateStatus.Ready) return
            if (!userAsked && (playing() || autoTried == apk.versionCode)) return
            if (!userAsked) autoTried = apk.versionCode
            _status.value = UpdateStatus.Installing(apk)
            cancelNotification()
            val problem = withContext(Dispatchers.IO) { verify(file, apk) }
            if (problem != null) { ready = null; file.delete(); _status.value = UpdateStatus.Failed(problem); return }
            if (RootInstaller.available()) {
                val failure = withContext(Dispatchers.IO) { RootInstaller.install(file, app.packageName) }
                if (failure == null) return  // The system is replacing this app.
                Log.w(TAG, "root install failed, using PackageInstaller: $failure")
            }
            if (!userAsked && !app.packageManager.canRequestPackageInstalls()) {
                // Android would only refuse; wait for the permission guide or a tap on Install.
                _status.value = UpdateStatus.Ready(apk)
                notifyReady(apk)
                return
            }
            try {
                withContext(Dispatchers.IO) { sessionInstall(file) }
            } catch (e: Exception) {
                Log.w(TAG, "PackageInstaller failed", e)
                _status.value = UpdateStatus.Ready(apk)
                notifyFailed(e.message ?: "Android would not install the update.")
            }
        }
    }

    private fun sessionInstall(file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Self-updates by the installer of record go in without a prompt on Android 12 and newer.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { output ->
                file.inputStream().use { it.copyTo(output, 64 * 1024) }
                session.fsync(output)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val result = PendingIntent.getBroadcast(app, id, Intent(app, InstallReceiver::class.java), flags)
            session.commit(result.intentSender)
        }
    }

    // ---- Results from PackageInstaller --------------------------------------------------------

    internal fun onConfirmationNeeded(context: Context, confirm: Intent) {
        val apk = (_status.value as? UpdateStatus.Installing)?.apk ?: ready?.first ?: return
        _status.value = UpdateStatus.Confirm(apk)
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            try { context.startActivity(confirm); return } catch (e: Exception) { Log.w(TAG, "could not show the install prompt", e) }
        }
        val open = PendingIntent.getActivity(context, 1, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        notify("Update to ${apk.versionName} ready", "Tap to install the new pianobar app.", open)
    }

    internal fun onInstallFailed(message: String) {
        val apk = ready?.first
        if (apk != null) _status.value = UpdateStatus.Ready(apk) else _status.value = UpdateStatus.Failed(message)
        notifyFailed(message)
    }

    // ---- Notifications ----------------------------------------------------------------------

    private fun notifyReady(apk: ServerApk) {
        val install = PendingIntent.getActivity(app, 2, Intent(app, MainActivity::class.java).setAction(ACTION_INSTALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        notify("pianobar ${apk.versionName} is ready", "It installs when you stop listening, or tap to install now.", install)
    }

    private fun notifyFailed(message: String) {
        val open = PendingIntent.getActivity(app, 3, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        notify("Couldn’t update pianobar", message, open)
    }

    private fun notify(title: String, text: String, tap: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(app).notify(NOTIFICATION, NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_note).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap).setAutoCancel(true).build())
    }

    private fun cancelNotification() = NotificationManagerCompat.from(app).cancel(NOTIFICATION)
}
