# Installer validation

Validated on 2026-09-11 using disposable containers on Proxmox VE 8.4 and the
Debian 12 amd64 template. The existing production player remained running.

| Check | Result |
| --- | --- |
| Fresh unprivileged LXC, 2 cores / 2GB RAM / 100GB disk | Passed |
| Hidden interactive credentials and private JSON automation input | Passed |
| Service installed, enabled at boot, and reachable on the LAN | Passed |
| Unauthenticated requests rejected | Passed |
| Account/web configuration permissions and password-free service logs | Passed |
| Generated AAC library playback, download, and nonzero browser PCM frames | Passed |
| Music survives service restart | Passed |
| Installer rerun retains config and music byte-for-byte | Passed |
| Published download-and-run Debian installer | Passed |
| Published Proxmox script matches the tested script | Passed |
| Existing container ID rejected without changes | Passed |
| Intentional installation failure removes its own new container/disk | Passed |
| Test containers, disks, source copies and temporary credentials removed | Verified |

Audio checks use generated fixtures and a synthetic account, without Pandora
account mutations or physical speakers. This validates the installation and local
playback path; it does not validate a particular user's Pandora credentials or
service availability. The native/web test suite also passed. Android debug build,
unit tests and lint passed with the project-specific package name.

The screenshots are captured from the real HTML/CSS/JavaScript using fictional
metadata and original vector artwork. No production account, terminal transcript,
browser address bar, private domain or server name appears in them.

## Repository privacy review

Gitleaks 8.30.1 scanned the current tracked source and the complete `main` history.
Its two findings were reviewed: both are public Pandora partner constants inherited
from upstream, not personal credentials. A separate targeted check found no known
personal hostnames, domains, account credentials or local home paths in the current
source. Build artifacts, private configuration, input credentials and signing keys
are excluded from version control.

Published history and the initial APK release were preserved. Historical Android
package identifiers and Git author metadata therefore remain in older revisions;
this is a current-source cleanup, not a history rewrite. New source uses a
project-specific Android package, with migration details in the Android README.
The original upstream copyright notices and license remain intact.

## Reproduce

`make test` covers native audio, web endpoints, credential validation and startup
logging. `python3 tests/installers.py` exercises the credential prompts through a
real pseudo-terminal and rejects invalid or world-readable input files.

`tests/installed.py --disposable` is an additional destructive smoke test intended
only for a temporary installer-created server using the synthetic account named
in that test. It changes settings and creates test music. Do not run it against a
real library. Use the Proxmox host to remove your test container and its disks after
testing, and remove any private credential input file supplied by your test runner.

## Native Android streaming (2026-09-12)

The native streaming build passes assembleDebug, JVM unit tests and lintDebug
(no lint errors; platform/style warnings remain). On an Android 15 / API 35
emulator, the three device tests pass: existing offline imports/playback, the
Player WebView/native audio boundary, and 95 seconds of screen-off streaming.
The streaming test verifies continued AudioTrack sample writes and at least five
listener heartbeats while the activity is backgrounded, then pause/resume,
connection recovery, Next, redirect rejection and authentication-expiry shutdown.
Fixtures use generated PCM and synthetic credentials; the production player and
library were not changed. Emulator sample delivery does not establish audible
quality on a physical phone or validate a real proxy identity provider.
