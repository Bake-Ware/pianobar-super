# pianobar for Android

A native Android app for a pianobar web server: control your radio, listen on
the phone, and keep songs on the phone for when there is no connection.
Android 8.0 or newer. The app starts with **no server configured**; there are no
bundled hostnames, accounts or tokens.

Built with Kotlin, Jetpack Compose (Material 3) and Media3.

## Connect

Enter your server's origin, such as `https://radio.example.com` or
`http://192.168.1.10:8765`, and choose **Connect**. If the server is behind a
sign-in page (for example Cloudflare Access), the app opens it in an in-app
browser; once you are signed in the app uses that session for everything. A
pianobar web password can be entered on the first screen or when the server asks.

- Web passwords are encrypted with a key held in the Android Keystore.
- Sign-in cookies live in the app's private WebView cookie jar.
- Credentials are only sent to the configured origin, and redirects are never
  followed: a redirect to an identity provider means "sign in again", and the
  app shows a **Sign in** banner.
- Public servers require HTTPS; HTTP is allowed for local addresses.
- Some identity providers (including Google OAuth) reject embedded browsers. Use
  another login method on the proxy, such as an email code.

**More → Change server or sign out** forgets the password and cookies.

## What's in the app

The bottom bar (a side rail on tablets and in landscape) holds five tabs:

- **Now playing**: artwork, song details and genres, YouTube and MusicBrainz
  links, and progress. Play/pause, Next, Love, Ban, Rest, Bookmark and volume
  all act on the server.
  - The **headphones** button listens on this phone.
  - The **DJ booth** holds the Talks, Picks music and Hops stations switches,
    what the DJ is saying (with Stop), the current set or hop status, and the
    station theme.
  - **Audio output** chooses where the server plays.
- **Stations**: search (press Go with one match left to play it), play now,
  **Play after this song**, and the station management commands.
- **Playlists**: create, rename, describe, reorder, play, shuffle, stop and
  delete. **Ask your DJ** builds a playlist from a description. Each playlist can
  be downloaded to the phone and played offline.
- **Library**: Songs, Artists, Albums and Genres tabs with search. Play a song
  or a whole group, add it to a playlist, or download it to the phone.
- **More**:
  - Downloads.
  - Server settings: the web player's Listening, Pandora, DJ, Connections and
    Web access tabs, including voice samples and "Try your DJ".
  - Listening devices: turn each browser or phone on or off and set its volume.
  - Every player command, and the player log.
  - App theme, this phone's name, and **Listen when the app opens**.

When the player asks a question, such as a new station name or a numbered
choice, it appears as a dialog with buttons for the choices.

## Listening on the phone

The headphones button joins the server as a listening device, like a browser
tab: the phone registers, keeps its lease with heartbeats, and plays the
server's live 16-bit PCM through `AudioTrack`.

- **Background play:** it runs in a Media3 media session service, so it keeps
  playing with the screen off.
- **System controls:** the notification, lock screen, Bluetooth and headset
  buttons all work.
  - Play/pause and Next act on the server.
  - Stop (or tapping the headphones again) stops listening on this phone.
- **Interruptions:** calls and other apps follow Android audio focus. Unplugging
  headphones stops listening.
- **Connection drops:** the app reconnects with backoff.

## Downloads and offline

**Download to this phone** fetches a saved song from `/api/download/…` as tagged
M4A or MP3 with cover art. The server needs `ffmpeg` and `ffprobe`.

- **Storage:** downloads are stored privately in the app, named by id. Playlists
  and the library are also cached, so you can browse them without a connection.
- **Playing:** downloaded songs play in the app's own player (ExoPlayer), with
  seeking and previous/next. Starting them stops the live stream, and **Back to
  the radio** returns to it.
- **Importing:** **More → Downloads → Import** adds audio files from the file
  picker. Files imported by earlier versions of the app are carried over.

## Build

Install JDK 17 and the Android SDK (`platforms;android-35`, `build-tools;35.0.0`).
Set `ANDROID_HOME` to the SDK location, or create an ignored `local.properties`
with `sdk.dir=...`. Then:

```sh
cd android
./gradlew assembleDebug testDebugUnitTest lintDebug
```

The installable development APK is `app/build/outputs/apk/debug/app-debug.apk`.
The [GitHub Actions template](ci/android.yml.example) runs the same checks. Copy
it to `.github/workflows/android.yml` to enable it.

For a signed release, provide a private keystore outside the repository:

```sh
export PIANOBAR_KEYSTORE=/absolute/path/to/private-release.jks
export PIANOBAR_KEY_ALIAS=pianobar
# PIANOBAR_KEYSTORE_PASSWORD (and PIANOBAR_KEY_PASSWORD) from your secret manager
./gradlew assembleRelease
```

Keep the same signing key for future releases so installs upgrade in place.
Never commit the keystore, passwords, SDK paths or APKs.

## Tests

Unit tests cover:

- server address validation and same-origin checks;
- the PCM frame parser (byte order, fragmented reads, keepalives, malformed
  frames);
- the API client against a mock server: credentials only for the origin, no
  Origin header, cookies kept, redirects and HTML meaning "sign in", error
  messages;
- snapshot parsing and DJ sound-tag display.

To try the app against a local server, run `pianobar --offline --port 18765` on
the development machine, then `adb reverse tcp:18765 tcp:18765`, and connect the
emulator to `http://localhost:18765`.
