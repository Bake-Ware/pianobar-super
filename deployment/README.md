# Server deployment

The [Proxmox installer](../scripts/proxmox-lxc.sh) creates a new unprivileged
Debian 12 LXC and runs the [Debian installer](../install.sh) inside it. The Debian
installer also works directly on a Debian 12 server. Both prompt for credentials
unless given a private `--credentials-file`. See the [quick start](../README.md).

## Installed layout

| Item | Location |
| --- | --- |
| Commands | `/usr/local/bin/pianobar`, `/usr/local/bin/pianobar-web` |
| Web assets | `/usr/local/share/pianobar/web/` |
| Runtime user and home | `pianobar`, `/var/lib/pianobar` |
| Pandora configuration | `/var/lib/pianobar/.config/pianobar/config` (0600) |
| Web preferences and password | `/var/lib/pianobar/.config/pianobar/web.json` (0600) |
| Saved music and cached artwork | `/var/lib/pianobar/songs/` |
| Service | `/etc/systemd/system/pianobar.service` |

The service reads its listen address, port and output mode from `web.json`, so
Settings changes take effect after a service restart. Fresh installs use
`0.0.0.0:8765`, password authentication with username `pianobar`, and browser audio.
Configured passwords are not printed in startup logs. The installer checks that
the authenticated web endpoint responds; account validity and station selection
are handled by the player. A failed Pandora login leaves the web settings reachable.

Build tools are retained for updates. Installers remove their staging directories
and private temporary credential copies. A caller-provided credentials file is
not deleted. Keep it outside source control and remove it when no longer needed.

## Proxmox behavior

Defaults are 2 cores, 2048MB RAM, 512MB swap, and a 100GB root disk. The disk holds
the OS and music. `STORAGE=local-lvm`, `TEMPLATE_STORAGE=local`, and `BRIDGE=vmbr0`
can be overridden; the bridge must provide DHCP and DNS. The installer downloads
a Debian 12 amd64 template only if one is not already cached. The shared template
cache is retained on success or failure.

No privileged container, host audio device, nesting feature, Rook worker, public
DNS record, or tunnel is installed. Manage the container with `pct enter <id>`;
no root SSH password is configured. Failed provisioning removes only the new
container tagged with that installer's unique ownership marker. Existing IDs
are rejected before any container mutation. Concurrent runs are locked out.

Tested on Proxmox VE 8.4 with a Debian 12 container. Other Proxmox releases have
not been validated. The [Proxmox container manual](https://pve.proxmox.com/pve-docs/pct.1.html)
describes the underlying creation and storage options.

## HTTPS and external access

The installer provides authenticated HTTP for a trusted LAN. For public access,
configure an HTTPS reverse proxy and an authentication layer of your choice.
`pianobar.nginx.conf` is an optional example, not installed automatically. Replace
its documentation-only hostname and connector addresses before use. For that
example, set `listen` to `127.0.0.1` in the private web settings and restart.

Translate only your exact public HTTPS Origin to the loopback origin expected by
the player's CSRF checks. Keep proxy buffering and nginx compression disabled for
live audio. Preserve `Accept-Encoding`: the app flushes its own streaming gzip
frames. Do not expose an unauthenticated alternate route around your login layer.

## Operations and updates

```sh
systemctl status pianobar
journalctl -u pianobar -n 60 --no-pager
df -h /var/lib/pianobar
systemctl restart pianobar
```

Run the Debian installer again **inside the existing container** to update. Do not
run the Proxmox installer to update: it creates a new container. Existing complete
configuration and music are retained; providing new credentials during an update
is rejected so they cannot silently replace a working account. Use Settings for
credential changes. Back up the private configuration and music before updates.
A successful update restarts playback. A failed build leaves the running service
alone; the Debian installer does not provide rollback after installation begins.

There is no automatic music eviction. Monitor free space and wait for saves to
finish before restarting. Browser playback is concurrent, not sample-synchronized.

`github-build.yml.example` preserves the upstream build workflow. To enable it,
copy it to `.github/workflows/build.yml` using a GitHub login with workflow permissions.
