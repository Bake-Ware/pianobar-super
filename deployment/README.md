# Example hosted deployment

These examples run pianobar as a dedicated user behind an authenticated reverse
proxy. Replace the example hostname and documentation-only connector addresses
in `pianobar.nginx.conf` before use.

- Source checkout: `/opt/pianobar`; installed command: `/usr/local/bin/pianobar`.
- Runtime user/home: `pianobar`, `/var/lib/pianobar`.
- Account configuration: `/var/lib/pianobar/.config/pianobar/config` (0600).
- Web preferences: `/var/lib/pianobar/.config/pianobar/web.json` (0600).
- Keep configuration, credentials and downloaded music outside the source tree.

Build with `make`, run `make test`, then install with `make install`. Create the
runtime user and writable home before installing the supplied systemd unit.
The unit pins the player to `127.0.0.1:8765` with browser audio output. Configure
Nginx with the supplied example and enable the services for automatic startup.
Pages request playback automatically by default. If autoplay is blocked, click
**Listen here** to allow sound. Disable automatic listening for a browser in
**Settings → This browser**; that preference is saved locally without a restart.

## Access and routing

Configure an authentication layer, such as Cloudflare Access, before exposing
the proxy publicly. Require valid application JWTs at the tunnel origin and
restrict the proxy to trusted connectors. Never commit account passwords,
service tokens, tunnel credentials, personal access policies or live configs.

Nginx translates only the configured public HTTPS Origin to the loopback origin
expected by the player. Other origins fail CSRF validation. Proxy buffering and
nginx compression are disabled for live audio. Preserve `Accept-Encoding` so
pianobar can compress status responses and flush compressed audio on each frame;
proxy-level compression can hold audio until a larger buffer fills. Change the unit and proxy together if
you change the listen address or port.

## Operations

```sh
systemctl status pianobar nginx
journalctl -u pianobar -n 60 --no-pager
df -h /var/lib/pianobar
systemctl restart pianobar
```

For updates, build and test the reviewed source before installing. Wait for
background saves to finish before restarting. There is no automatic music
eviction; monitor free disk space. Browser clients share station and transport
controls, and choose **Listen here** independently. Playback is concurrent,
but not sample-synchronized.

## GitHub build workflow

`github-build.yml.example` preserves the upstream build workflow for the `main`
branch. To enable GitHub Actions, move it to `.github/workflows/build.yml` and
push using credentials authorized to manage workflows.
