# pianobar SUPER

Your Pandora stations, a browser player, and a growing offline music library—on
your own server. Control it from a desktop, phone, or terminal.

![The dark web player with the SUPER logo](docs/screenshots/player-dark.png)

- Play and manage stations, rate songs, and use the familiar pianobar hotkeys.
- Save songs automatically when they load; skipping does not cancel the save.
- Browse saved music with cached album art, play offline, or download M4A/MP3 files.
- Listen through browsers, host speakers, or both. Each browser can join independently.
- Dark/light themes, mobile navigation, settings, and a collapsible shortcut legend.
- Android companion with a configurable server and on-device offline library.

## Install on Proxmox

Run in the **Proxmox host's root shell**:

```sh
curl -fsSL https://raw.githubusercontent.com/Bake-Ware/pianobar-super/main/scripts/proxmox-lxc.sh -o /tmp/pianobar-lxc.sh
bash /tmp/pianobar-lxc.sh
```

Enter your **Pandora email and password**, then a separate **web login password**.
Password entry is hidden. The remaining installation is unattended: it downloads
a Debian 12 template, creates an unprivileged LXC, builds the app, enables its
service, and prints the web address. Sign in as **pianobar**, then select a station.

Defaults: **2 cores, 2GB RAM, 100GB disk**, DHCP on `vmbr0`, root disk on
`local-lvm`, templates on `local`, and the next unused container ID. A 100GB disk
leaves room for several thousand tracks; actual capacity depends on duration and
quality. Music is never automatically evicted, so monitor disk usage.

Customize without editing the script:

```sh
STORAGE=local-lvm BRIDGE=vmbr0 DISK_GB=150 CT_ID=250 bash /tmp/pianobar-lxc.sh
```

Also available: `TEMPLATE_STORAGE`, `MEMORY_MB`, `CORES`, and `CT_HOSTNAME`.
The script refuses an occupied ID. If installation fails after creation, it removes
only the new container belonging to that run. It does not modify Proxmox repositories
or existing containers. A working DHCP server, DNS, Internet access, and suitable
storage are required. See [deployment details](deployment/README.md).

## Install on a Debian server

On **Debian 12**, run as root (or use `sudo bash /tmp/pianobar-install.sh`):

```sh
curl -fsSL https://raw.githubusercontent.com/Bake-Ware/pianobar-super/main/install.sh -o /tmp/pianobar-install.sh
bash /tmp/pianobar-install.sh
```

The same credential prompts configure a system service. The installer handles
dependencies, compilation, and startup. Run it again to update while retaining
your configuration and saved songs. Updates restart playback. Debian 12 is
intentional: the native player currently targets FFmpeg 5.

Both scripts can use a local checkout with `--source /path/to/pianobar-super`.
For fully unattended provisioning, supply `--credentials-file /private/credentials.json`.
That file must have mode `0600` and exactly these JSON string fields: `user`,
`password`, and `web_password`. The web password must be at least 12 characters.
Use your secret manager to create it outside the checkout. The installer removes
its temporary copies; you remain responsible for deleting the original input file.
Never pass passwords on the command line or commit them.

## Listen

Open `http://<server-ip>:8765/`. **Listen here** joins this browser; a click may
be needed because of browser autoplay rules. Disable automatic listening in
**Settings → This browser** to use a page as a remote control instead.

Station selection and transport controls affect the shared player. Stopping
listening in one browser leaves other listeners playing. Browsers play concurrently,
but are **not synchronized across rooms**. Browser audio is compressed PCM and
can use up to roughly 1.4Mbps per listener before compression at 44.1kHz stereo.

The installer enables password-protected LAN access. HTTP is intended for a trusted
network; use an authenticated HTTPS reverse proxy for Internet access. No public
domain, tunnel, or external login provider is bundled or provisioned.

## Saved music

![The library with saved tracks and download buttons](docs/screenshots/library-light.png)

Tracks download in the background as they load, independently of playback. Wait
for the **Saving … songs for offline** message to clear before shutting down.
Only completed saves appear in Library; identical song/quality entries are
deduplicated. Saving uses an additional download request, so it consumes extra bandwidth.

Choose **Go offline** to play saved songs. Network failures can also trigger offline
fallback when saved music is available. **Reconnect** returns to Pandora.
Library's **Download** button exports AAC as M4A or MP3 as MP3, with metadata and
available artwork, without re-encoding. Server saves live under
`/var/lib/pianobar/songs` for installer-managed deployments.

## Song metadata and artwork

The web player enriches the current song using MusicBrainz and Cover Art Archive,
without API keys. Lookups run in the background and cache genres, release dates,
and recovered artwork under the library folder. Artist genres are labeled when
track genres are unavailable. Matches require the same artist and title; artwork
uses the named album when one is supplied. Coverage varies by release.

Artwork falls back from the local cache to Pandora and then Cover Art Archive.
Cached metadata and artwork remain available without a connection. Cached-song
playback can still fetch missing metadata when internet access is available; set
`PIANOBAR_METADATA_NETWORK=0` to disable those external lookups. Successful metadata is
refreshed after 30 days; missing matches after a day; service errors after five
minutes. MusicBrainz requests are limited to one per 1.1 seconds.

**Find on YouTube** opens a search for the current artist and song. YouTube is
only contacted when you click the link; the player does not embed videos.

## LLM DJ and voice endpoint

Open **DJ mode** in Now playing, set a station theme and personality, and enable
**Let the LLM pick songs from my local cache**. The server chooses validated
tracks from your saved library, queues the next song while the current song
finishes, and prepares its spoken introduction whenever Up next changes,
including manual queue changes while DJ mode is active. Text and voice are
prepared in the background; outdated results are discarded and the prepared
intro plays when that song starts. Recent tracks are excluded from
selection when alternatives exist. Large libraries use a rotating sample of
100 candidates per selection. Missing LLM responses leave ordinary cached
playback running.

Enable **Hear DJ introductions in this browser** to listen, or submit your own
line with **Speak**. With the station picker off, introductions can still be
generated for the current Pandora or cached song.
Voice plays in the enabled browser; its music volume is reduced while speaking.
Song selection, introduction generation and automatic voice preparation only
run while an enabled, unmuted browser has a live audio connection and recent
heartbeats reporting actual music playback. Pause, stop, disconnect or routing
away suspends that work; the DJ continues when browser playback resumes.
Settings previews remain available with music stopped. Set **DJ name** and
**Your name** to personalize introductions; both apply immediately on save.
In Settings, size DJ sets by **Number of songs** (1–30) or **Minutes of music**
(1–120). The LLM chooses an ordered set of local cached songs and prepares one
summary for its beginning. Timed sets aim for the target using whole songs,
up to 30; the queue shows their actual duration. Set sizing applies to the next
selection when saved. **Play DJ intros over music** keeps the music underneath
the voice. Uncheck it to pause the shared player and preserve the browser's
audio buffers until the intro finishes. Stop, speech failure or leaving the
page releases this airtime; abandoned reservations expire after 45 seconds.
An explicit listener pause remains paused. Voice and intro playback choices
apply on save without restarting.
The DJ stays silent when this browser is not playing music, including pause,
mute and routing away; stopping listening cancels ongoing speech. Host speakers
keep their volume. Changing tracks or disabling DJ mode stops the
voice. The shared DJ station starts off when the server starts; each browser
starts with voice off. Starting from Pandora switches to cached-song playback.
Reconnecting to Pandora stops the cached-song DJ. Disabling song selection
clears the prepared queue; cached playback can continue normally.

Use **Settings → LLM DJ & voice** to edit endpoints, model, keys, voice,
certificate path, default theme/personality and metadata fetching. The voice
dropdown loads the active voice server’s `/voices` catalog and preserves your
selection if that server is unavailable. **Sample voice** previews the selected
voice before saving, even when music is stopped. **Save settings** applies voice
selection immediately without restarting the player or server. Blank key
fields preserve saved keys; explicit Forget options clear them. Use **Save &
restart player** to apply other provider changes. Environment overrides are labeled and their
fields are disabled in the editor.

Alternatively, set these environment variables before starting the server, or put the
corresponding fields in private `$XDG_CONFIG_HOME/pianobar/dj.json` (default
`~/.config/pianobar/dj.json`). Environment variables take precedence. JSON fields
are `llm_url`, `model`, `llm_key`, `style`, `tts_url`, `voice`, `tts_key`, and optional `tts_ca`.

| Variable | Purpose |
| --- | --- |
| `PIANOBAR_DJ_URL` | Full HTTP chat endpoint, such as a local Ollama `/api/chat` URL |
| `PIANOBAR_DJ_MODEL` | Model available at that endpoint |
| `PIANOBAR_DJ_KEY` | Optional bearer token, kept on the server |
| `PIANOBAR_DJ_STYLE` | Default personality for API callers |
| `PIANOBAR_TTS_URL` | Optional HTTP voice endpoint |
| `PIANOBAR_TTS_VOICE` | Voice name sent to the voice endpoint or local engine |
| `PIANOBAR_TTS_KEY` | Optional voice endpoint bearer token |
| `PIANOBAR_TTS_CA` | Optional certificate file for a private HTTPS voice endpoint |

The chat adapter sends `{model, messages, stream: false}` and accepts text from
`message.content` or `choices[0].message.content`. This supports the
[Ollama chat API](https://docs.ollama.com/api/chat). Use a model already installed
on your provider. URLs and tokens are never included in public settings.

The voice adapter sends `{text, voice}` and expects WAV, MP3, or Ogg audio in the
HTTP response with the matching audio content type. Without a configured voice
URL, `/api/voice` uses `espeak-ng` or `espeak` when installed on the server. If
neither is available, the browser DJ can use its built-in speech voice; the
server voice endpoint reports that a speech provider needs configuration.

`integrations/voice-tts.py` adds an authenticated `/api/voice` route to an
existing FastAPI voice service through `install(app, authorize)`. It uses the
service's loaded speech provider and key store, returns WAV audio, bounds text
and request sizes, and admits one synthesis at a time. A private HTTPS
certificate can be supplied through `PIANOBAR_TTS_CA`; TLS verification remains
active.

An external LLM agent can read song context and submit its own introductions:

| Endpoint | Behavior |
| --- | --- |
| `GET /api/dj` | Current song, metadata, provider readiness, and `songKey` |
| `POST /api/dj` | Generate `{text, songKey}`; optional `{style}` request |
| `POST /api/dj/control` | `{enabled, theme?, style?}` starts/stops the shared cached-song station |
| `POST /api/dj/pick` | `{play?, theme?, style?}` selects one cached song; queues by default, `play: true` plays immediately |
| `POST /api/dj/announce` | Broadcast `{text, songKey?}` to browsers with DJ mode enabled |
| `POST /api/dj/airtime` | Reserve/release temporary intro airtime for a registered listening browser |
| `POST /api/voice` | Convert `{text, voice?}` to audio, without broadcasting it |

For example, with the server running locally:

```sh
curl -H 'Content-Type: application/json' \
  -d '{"text":"Coming at ya with some London punk!"}' \
  http://localhost:8765/api/dj/announce

curl -H 'Content-Type: application/json' \
  -d '{"text":"Coming at ya with some London punk!"}' \
  http://localhost:8765/api/voice --output dj.wav
```

Include `songKey` from `GET /api/dj` when an introduction belongs to a specific
song, so an outdated announcement is rejected after a track change. The existing
web authentication and origin checks apply to DJ and voice endpoints. Text is
limited to 700 characters. Generation and voice requests have bounded concurrency
and timeouts; repeated introductions for the same song and personality reuse the
session cache.

## Android and mobile

<img src="docs/screenshots/player-mobile.png" alt="Mobile player with artwork and touch controls" width="300">

The [Android release](https://github.com/Bake-Ware/pianobar-super/releases) is also
linked from the web app's **Settings → Android app**. Android 8.0 or later is required.
Enter your own server address in the app's Settings; it ships without one.

The app uses native background audio with lock-screen controls. Sign in inside
Player, select a station, then tap **Listen**. Its login session is separate from
your installed browser; see the Android README for proxy login limitations.
For offline device playback, download songs from the web Library, then use
**Downloads → Add downloaded tracks** in the APK to import them. These local copies
play without the server, including with the screen off.
See [Android setup, builds, and package migration](android/README.md).

## Terminal and development

For a local build, install the dependencies listed in [install.sh](install.sh), then:

```sh
make
./pianobar             # Web interface enabled by default
./pianobar --cli       # Terminal only
./pianobar --offline   # Play the local library
make install-user     # Install the command in ~/.local/bin
make test
```

Ensure `~/.local/bin` is on your `PATH`; no alias is needed. The terminal help lists
your configured keys, including the browser-opening shortcut. Further configuration
is documented in the [manual](contrib/pianobar.1) and [example config](contrib/config-example).
The [REST API and optional Rook integration](integrations/README.md) expose controls
and browser routing for automation.
See the [installer validation report](docs/validation.md) for tested paths and privacy-review scope.

Screenshots above use fictional music and original generated vector artwork, with
the actual web UI. To reproduce them in an isolated Python environment:

```sh
pip install playwright
python -m playwright install chromium --only-shell
python scripts/screenshots.py
```

## Credits

Based on [pianobar](https://codeberg.org/purplesym/pianobar), created by
**Lars-Dominik Braun** and maintained by the upstream contributors. This fork adds
the web interface, local caching/offline playback, browser streaming, installers,
and Android companion. The original copyright notices and [MIT license](COPYING)
are preserved. Pandora and Android belong to their respective owners; this project
is independent and is not endorsed by them.
