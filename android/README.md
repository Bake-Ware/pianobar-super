# pianobar for Android

A generic Android companion for a pianobar web server, with an on-device audio
library. Android 8.0 or newer is required. The app starts with **no server
configured**; there are no bundled hostnames, account credentials, or access tokens.

## Connect

1. Open **Settings** in the app.
2. Enter your server's origin, such as `https://radio.example.com` or
   `http://192.168.1.10:8765`, then choose **Save server**.
3. Choose **Player**, and sign in using your server's normal authentication flow.

The Player screen keeps station selection and settings in an in-app WebView, but
**live audio runs natively in a foreground Android media service**, independently
of the WebView and its timers. After signing in and selecting a station, tap
**Listen** above the page. Locking the screen or leaving Player keeps audio running.
The media notification and lock screen provide Play/Pause, Next and Stop.
Pause affects this device only; Next advances the shared server player.
Unplugging headphones pauses playback. Other audio apps and calls obey Android
audio focus. Starting a downloaded track stops live streaming, and vice versa.

The native service reads the existing framed 16-bit PCM API using AudioTrack;
no server update or transcoding is required. It maintains its own listener lease,
refreshes metadata, reconnects after interrupted connections, and holds CPU/Wi-Fi
locks while listening. Mono/stereo at 8–192 kHz are accepted; malformed and
oversized frames are rejected. It honors server-side listener volume and routing.
Concurrent listeners remain unsynchronized across rooms.

Sign in **inside the app**: Custom Tab/browser sessions cannot be imported.
HTTP Basic web passwords stay in process memory; the WebView's private cookie
store supports cookie-based proxy sessions. Credentials are only attached to the
configured origin; native API requests never follow login redirects. If a session
expires, playback stops and asks you to sign in again. Certificates are validated
normally; there is no certificate-error bypass or JavaScript-to-native bridge.
Some identity providers (including Google OAuth) reject embedded browsers. Such
providers require a supported in-app proxy login method (for example, an already
configured email-code option) or the password-protected LAN server. This build
does not add an external-browser OAuth pairing flow or change proxy policy.

Server URLs must be origins without paths, queries, or embedded credentials.
Public servers require HTTPS; HTTP is allowed for local addresses and local hosts.

## Download and listen offline

In the server's **Library**, choose **Download** beside a saved song. Choose a destination in the Android file picker. The app saves the audio
using the same authenticated server session. Keep the app running until saving completes. Cached AAC exports as M4A,
and cached MP3 exports as MP3. Audio is copied without re-encoding; metadata and
available JPEG/PNG artwork are included. This requires a server version with the
`/api/download/{saved-song-id}` endpoint and `ffmpeg`/`ffprobe` installed.

Return to the app and open **Downloads → Add downloaded tracks**. Select one or
several files from Android's file picker, usually in Downloads. The app imports
its own private copies and deduplicates identical files. These play without the
server, including with the screen off, using a foreground media service and
lock-screen play/pause/previous/next controls. No broad storage permission is
needed. Removing a track deletes only the app's imported copy; the browser's
original download and server cache are retained. Uninstalling the APK deletes
its private library. Imports currently accept audio files up to 100 MB each.

Downloading and importing are two explicit steps: the saved document remains yours, while the offline library holds its own copy. Server-side
**Go offline** remains separate from the Android app's on-device library.

## Build

New builds use the project-specific application ID `org.pianobarsuper.app`.
This replaces the initial release's domain-based application ID. Android treats
it as a separate app: configure the server again and import your downloaded
tracks. Keep the old app until you have recovered any files you need; its private
library is not automatically migrated. The initial published APK may still use
the earlier identity; check the release notes before installing.

Install JDK 17 and the Android SDK (`platforms;android-35`, `build-tools;35.0.0`).
Set `ANDROID_HOME` to the SDK location or create an ignored `local.properties`
with `sdk.dir=...`. Then:

```sh
cd android
./gradlew assembleDebug testDebugUnitTest lintDebug
```

The installable development APK is `app/build/outputs/apk/debug/app-debug.apk`.
The [GitHub Actions template](ci/android.yml.example) runs the same checks and
uploads the APK as the `pianobar-android-debug` artifact. To enable it, copy the
template to `.github/workflows/android.yml` and commit it using a GitHub login
with permission to write workflows. The template is not active until installed. Debug signing keys can differ between machines
and CI runs; use a stable release key for upgradeable distributed APKs.

For a signed release, provide an existing private keystore outside the repository:

```sh
export PIANOBAR_KEYSTORE=/absolute/path/to/private-release.jks
export PIANOBAR_KEY_ALIAS=pianobar
# Set PIANOBAR_KEYSTORE_PASSWORD and, if different, PIANOBAR_KEY_PASSWORD
# through your local secret manager or CI secrets, not a committed file.
./gradlew assembleRelease
```

Without signing variables, `assembleRelease` produces an unsigned APK. Keep the
same private signing key for future releases. Never commit the keystore,
passwords, SDK paths, APK outputs, or runtime configuration. The Gradle wrapper
is checked in; build outputs and local configuration are ignored.

## Device tests

Use a disposable Android emulator with API 35, then run:

```sh
./gradlew connectedDebugAndroidTest
```

These tests reset the app's test configuration/library and verify blank initial
configuration, server URL persistence, imported-file deduplication, playback
after the source disappears, background/screen-off playback, pause/resume, and
removal. Live-stream tests use an authenticated local PCM fixture and verify 95 seconds
of screen-off playback, listener renewal, pause/resume, connection recovery,
Next, rejection of login redirects, and shutdown on expired authentication.
Parser unit tests cover fragmented reads, byte order, keepalives and malformed
frames. Real proxy sign-in and physical-device audio require manual validation;
tests do not contain real credentials or bypass authentication.
