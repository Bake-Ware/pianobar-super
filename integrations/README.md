# Pianobar REST API and Rook controls

The browser interface, REST callers and Rook control the same player. The API is
served by `pianobar-web` on port 8765 by default. There is one shared station and
transport; browser routing selects recipients of that same live audio stream.
Streams are concurrent, not sample-synchronized. Per-browser volume multiplies
the player's master volume.

## Authentication

Use the existing web authentication. Direct network mode uses HTTP Basic with
username `pianobar` and the configured web password. Hosted deployments should
use their authenticated HTTPS proxy (including its service-token headers for
automation). Loopback requests on the LXC use `http://127.0.0.1:8765`.
All authenticated users have the same control access; there is no separate
administrator role. Browser IDs identify tabs, not users or credentials.
Host, Origin and cross-site checks apply to every endpoint. POST requests require
`Content-Type: application/json` and accept at most 4096 bytes.

## Player endpoints

| Method | Path | Request / response |
| --- | --- | --- |
| GET | `/api/state` | State, prompt, pending flag, output and revision. Optional `?after=N` long-polls for up to 20 seconds. |
| GET | `/api/actions` | All 32 native actions with IDs, keybindings, descriptions and current availability. |
| GET | `/api/stations` | Stations and stable station IDs. |
| GET | `/api/library` | Saved tracks, IDs and artwork URLs. |
| GET | `/api/settings` | Settings; password values are omitted. |
| POST | `/api/command` | Exactly one operation: `action`, `selectStation`, `playSaved`, `output`, or `text` with `promptId`. |
| POST | `/api/settings` | `{"settings": {...}, "apply": true}` saves and restarts the native player. `apply` defaults to false. |
| GET | `/api/artwork/ID` | Cached artwork. |

Commands return `{"ok":true}` when accepted, not when Pandora finishes the
operation. Read state afterwards. When `pending` is true, wait; when a prompt is
active, answer it using its current ID. This covers station creation, deletion,
renaming, seeds, ratings, bookmarks, genre selection, history, QuickMix and every
other native control without bypassing their confirmation prompts. An unavailable
action, stale prompt, malformed command or busy player returns HTTP 400 with an
`error`. Authentication failures return 401 and rejected origins/hosts return 403.

```json
{"action":"act_songpause"}
{"selectStation":"STATION_ID"}
{"playSaved":"SAVED_TRACK_ID.mka"}
{"output":"browser"}
{"text":"answer","promptId":42}
```

## Browser endpoints

Every open page registers independently and renews its lease every two seconds.
Pages start silent. A page expires after 90 seconds without a heartbeat; closing
a page attempts immediate unregistration. A reload gets a new ID. Browser names
can be edited beside **Listen here**, or through the API. Names need not be unique.

| Method | Path | JSON body / response |
| --- | --- | --- |
| GET | `/api/clients` | `clients` with id, name, enabled, volume, ready, status, lastSeenSeconds; also leaseSeconds. |
| POST | `/api/clients/register` | `{"name":"Kitchen"}` → registered page. |
| POST | `/api/clients/heartbeat` | `{"id":"ID","ready":true,"status":"playing"}` → current desired settings. |
| POST | `/api/clients/update` | `{"id":"ID","name":"Kitchen","volume":0.5,"enabled":true}`; update any subset. |
| POST | `/api/clients/route` | `{"ids":["ID1","ID2"]}`, `{"mode":"all"}`, or `{"mode":"none"}`. |
| POST | `/api/clients/unregister` | `{"id":"ID"}`. Idempotent. |
| GET | `/api/audio` | Registered stream; supply `X-Pianobar-Client: ID`. |

Routing replaces the entire browser recipient set atomically. An unknown or
expired ID rejects the whole request. `all` means all currently registered pages;
new pages still start silent. `none` silences browser recipients and leaves native
speakers unchanged. A nonempty route switches `host` output to `both` so browser
audio is available while retaining native speakers. Individual `enabled` changes
do not change the global output mode. `volume` accepts 0 through 1.

The server gates PCM delivery for each destination, including already-open
streams. Pages apply routing and gain changes on their next heartbeat; a small
amount of already-delivered audio can remain buffered. Status is page-reported:
`idle`, `waiting`, `playing`, `blocked`, or `error`. `enabled` is the requested
destination state; it does not prove sound is audible. A browser may require a
local **Listen here** click before its first remotely requested playback. In that
case it reports `blocked`; after activation, routing can stop and resume that
page remotely. Sleeping or suspended tabs cannot guarantee immediate control.

Audio uses a binary stream of 20-byte network-endian headers (five uint32 fields:
PCM payload bytes, sample rate, channel count, little-endian flag, epoch), followed
by signed 16-bit interleaved PCM. Zero-length frames are keepalives.

## Rook installation and calls

Install `pianobar-rook` as `/usr/local/bin/pianobar-rook` (mode 0755). Register each
entry in `rook-caps.json` through `customcap.add` on the player worker. Rook persists
these definitions across worker restarts. The resulting names begin with
`cmd.pianobar-`.

The adapter uses loopback and reads the web password, if configured, from
`/var/lib/pianobar/.config/pianobar/web.json`. Override `PIANOBAR_API_URL` and
`PIANOBAR_WEB_CONFIG` for another installation. It never prints that password.
The worker must have permission to read the settings file. Supply credential
changes or secret prompt responses directly over authenticated REST: Rook retains
cap arguments in its journal.

| Capability | Arguments |
| --- | --- |
| `cmd.pianobar-state`, `-actions`, `-stations`, `-library`, `-settings`, `-browsers` | None |
| `cmd.pianobar-songpause`, `-songplay`, `-songnext`, etc. | None; one cap for every `act_` action. Native `act_settings` is `cmd.pianobar-account-settings`; `cmd.pianobar-settings` reads web settings. |
| `cmd.pianobar-command` | `json`: JSON object containing one command. |
| `cmd.pianobar-station`, `cmd.pianobar-saved` | `id`: station/track ID. |
| `cmd.pianobar-output` | `mode`: host, browser, or both. |
| `cmd.pianobar-settings-update` | `json`: settings request object encoded as JSON. |
| `cmd.pianobar-browser-update` | `json`: browser update object encoded as JSON. |
| `cmd.pianobar-route` | `targets`: `all`, `none`, or a JSON array encoded as a string. |

Example Rook call arguments:

```json
{"worker":"pianobar","cap":"cmd.pianobar-route","args":{"targets":"[\"ID1\",\"ID2\"]"}}
```

Rook returns the adapter's JSON in `stdout`, plus its exit code and `ok`. A rejected
API request makes the cap fail. Read `cmd.pianobar-actions` for current availability
and `cmd.pianobar-state` for prompts before issuing dependent operations.

## Verification

Run `make test` for native and HTTP coverage. For real browser playback tests,
install Playwright and Chromium in a test environment, then run
`python tests/network.py --browser` and `python tests/web.py --browser`.
