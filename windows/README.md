# pianobar SUPER for Windows

A desktop shell for the pianobar SUPER web client: a Win32 window around
Microsoft Edge WebView2, so the player behaves like a real app instead of a
browser tab.

Everything the player does — stations, library, offline mode, settings,
prompts — is the web client itself, so this app never drifts from the browser
or the Android app. The shell only adds things a tab cannot do.

## Features

- **Always on top** — tray menu, the pin button in widget mode, or `Ctrl+Alt+T`
- **Minimize / close to tray** — the close button hides to the tray; `Ctrl+Alt+P`
  toggles the window; tray menu has *Exit* to really quit
- **Widget mode** — a compact 344x240 borderless window with rounded corners:
  album art, track info, transport, volume. `Ctrl+Alt+M` or the *Widget mode*
  button in the full player. The widget's expand button goes back to the full
  player.
- **Server address dialog** — tray menu *Change server…*; supports
  `http://user:pass@host:port` (Basic auth) for networked servers
- **Document title in the tray** — the tooltip shows the current station/track
- **Single instance** — launching again focuses the existing window
- **Autoplay** — the WebView2 runtime plays audio without a click, so the
  widget joins playback immediately like a media app should

## Requirements

- Windows 10 21H2 or newer (11 recommended for rounded widget corners)
- [WebView2 Runtime](https://developer.microsoft.com/en-us/microsoft-edge/webview2/)
  (preinstalled on Windows 11 and most current Windows 10 machines)

## Build

```bat
cd windows
build.bat
```

The script downloads the pinned WebView2 SDK from NuGet into `sdk/` on first
run, then compiles with the Visual Studio 2022 C++ tools (the "Desktop
development with C++" workload). Output: `pianobar-desktop.exe` (statically
linked, no runtime dependencies beyond the WebView2 Runtime).

## Run

```bat
pianobar-desktop.exe                          :: default: https://pianobar.bake.systems
pianobar-desktop.exe --server=http://192.168.1.198:8080
pianobar-desktop.exe --server=https://pianobar.bake.systems --widget
pianobar-desktop.exe --topmost --no-tray
```

Settings persist in `%APPDATA%\pianobar-super\desktop.json`; the WebView2
profile (cookies, including Cloudflare Access logins) lives in
`%LOCALAPPDATA%\pianobar-super\webview`.

## Hotkeys

| Keys | Action |
| --- | --- |
| `Ctrl+Alt+T` | Toggle always on top |
| `Ctrl+Alt+M` | Toggle widget mode |
| `Ctrl+Alt+P` | Show / hide window |
