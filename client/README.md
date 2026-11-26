# VoD Client

The VoD system's desktop app. It works like NVIDIA ShadowPlay: OBS keeps streaming to your VoD
server in the background, and a key press saves the moment that just happened.

## For users

1. Download **VoD-Client.exe** (Windows) or **VoD-Client-x86_64.AppImage** (Linux) from the
   Releases page.
2. Open it. On Linux, first mark the file as executable: right-click → Properties → "Allow
   executing", or run `chmod +x VoD-Client-x86_64.AppImage`.
3. Click **Sign in with Google**.
4. If OBS Studio isn't installed, click **Install OBS Studio**.

That's it. You're live, and these hotkeys work from any game or app:

| Hotkey    | What it does                                                        |
|-----------|---------------------------------------------------------------------|
| `Alt+F10` | Save the last 30 seconds as a clip                                  |
| `Alt+F9`  | Start a section; press again to save everything since as a video    |
| `Alt+F11` | Add a marker                                                        |

On a Mac the same hotkeys use **Ctrl+Option** (`⌃⌥F10`, `⌃⌥F9`, `⌃⌥F11`), because macOS 15
refuses shortcuts whose only modifier is Option. On a laptop keyboard, also hold `fn` for the F
keys. VoD Client doesn't need Accessibility or Input Monitoring permission.

A notification confirms each one. Clip length, hotkeys and start-up behaviour are under
**Settings**. Closing the window keeps VoD Client running in the tray, so the hotkeys keep
working. VoD Client opens automatically when you sign in to your computer (you can turn this off
in Settings).

On Wayland desktops (GNOME, KDE Plasma), your desktop asks once whether to allow VoD Client's
shortcuts, and you change them in its keyboard settings.

## For maintainers

### Server setup (once)

- In the Google Cloud console, add `http://localhost:53682/callback` as an **Authorised redirect
  URI** on the OAuth client the backend uses (`GOOGLE_CLIENT_ID`). The app signs in through that
  client, so it needs no secret of its own.
- Optionally set `RTMP_PUBLIC_URL` in the backend's `.env` if RTMP ingest isn't at
  `rtmp://<server host>:1935/live`.

The app gets the Google client ID, RTMP URL and website address from the public
`GET /api/v1/client/config` endpoint, so none of them are built in.

### Publishing a release

1. Set the repository variable `VOD_SERVER_URL` to the backend's address, e.g.
   `https://vod.example.com`. It's baked into the app, so users never type it.
2. Push a tag:

   ```bash
   git tag client-v1.0.0 && git push origin client-v1.0.0
   ```

`.gitea/workflows/client-release.yml` then builds both downloads and attaches them to a Gitea
release.

### Building locally

```bash
cd client
make appimage windows VERSION=1.0.0 SERVER_URL=https://vod.example.com   # → dist/
```

Building needs Go 1.24+ and cgo. For Linux, install
`gcc libgl1-mesa-dev xorg-dev libwayland-dev libxkbcommon-dev`. Windows builds cross-compile with
`gcc-mingw-w64-x86-64`. Builds without `SERVER_URL` ask for a server on first launch, which is
handy for development.

macOS builds with `go build ./cmd/vod-client` on a Mac but isn't part of the release yet. Its
hotkeys use Carbon's `RegisterEventHotKey` (`internal/hotkey/register_darwin.go`), which only
receives the registered combinations and so needs no Accessibility or Input Monitoring
permission. Option-only combinations are rejected on every path (defaults, saved settings, the
hotkey recorder), since macOS 15 refuses to register them.

### How it works

| Package            | Responsibility                                                                  |
|--------------------|---------------------------------------------------------------------------------|
| `internal/ui`      | Fyne window, setup screens, settings, tray menu                                 |
| `internal/app`     | `Controller`: the sign-in → OBS → live state machine the UI renders             |
| `internal/api`     | Backend client; bearer access token, refresh-token rotation                     |
| `internal/auth`    | Google sign-in through the browser and a loopback page                          |
| `internal/obs`     | Find, install (Flatpak/apt/dnf/pacman/zypper, winget, Homebrew), launch and drive OBS over obs-websocket v5 |
| `internal/hotkey`  | Global hotkeys: key grabs on X11/Windows/macOS, the GlobalShortcuts portal on Wayland |
| `internal/desktop` | Start on login, the Linux applications menu entry, tray detection               |
| `internal/control` | Single instance: a second launch brings up the first one's window               |

Settings, the session and a log (`vod-client.log`, useful in bug reports) are stored in
`~/.config/vod-client`, `%AppData%\vod-client` or `~/Library/Application Support/vod-client`.

### Tests

```bash
go test -race ./...
```

The tests run against an in-memory fake backend (`internal/api/apitest`), a fake obs-websocket
server (`internal/obs/obstest`) and a fake GlobalShortcuts portal on a private D-Bus (skipped
without `dbus-daemon`). The controller tests launch the test binary itself as a stand-in for OBS,
which covers install, launch and shutdown. The UI tests run headless on Fyne's test driver.
`SHOTS=/tmp/shots go test -run TestScreens ./internal/ui` renders every screen to PNG.
