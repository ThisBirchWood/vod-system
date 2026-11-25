# VoD Client

A desktop companion for the VoD system that works like NVIDIA ShadowPlay. It keeps OBS streaming to
your VoD server in the background, and global hotkeys capture what just happened.

On launch it:

1. logs you in with Google (once; the session is then refreshed automatically),
2. installs OBS Studio if it is missing,
3. starts OBS minimised to the tray, points it at your VoD server's RTMP ingest with your stream
   key, and starts streaming,
4. listens for hotkeys until you press Ctrl+C.

| Action  | Default   | What it does                                                                     |
|---------|-----------|----------------------------------------------------------------------------------|
| Clip    | `Alt+F10` | Saves the last `clip_seconds` (default 30s) as a clip, like instant replay       |
| Section | `Alt+F9`  | First press marks a start, second press saves everything since as a VoD          |
| Marker  | `Alt+F11` | Drops a marker on the stream                                                     |

A desktop notification confirms each action and tells you when the server has finished processing.

## Building

```bash
cd client
go build -o vod-client ./cmd/vod-client
```

Global hotkeys on Linux and macOS use cgo. On Linux, install the X11 headers first
(`apt install libx11-dev`). A `CGO_ENABLED=0` build still works, but without hotkeys: use the
commands below instead.

## Setup

The client signs in with the same Google OAuth client as the web frontend.

1. In the Google Cloud console, open the OAuth client the backend uses (`GOOGLE_CLIENT_ID`) and add
   `http://localhost:53682/callback` as an **Authorised redirect URI**. Use a different port by
   setting `oauth_redirect_port`.
2. Run the client once and point it at your server:

   ```bash
   vod-client --server https://vod.example.com --google-client-id <GOOGLE_CLIENT_ID>
   ```

   Both values are saved, so after this a bare `vod-client` is enough.

## Commands

```
vod-client [run]           log in, launch OBS, stream and listen for hotkeys
vod-client login | logout
vod-client clip            same as the clip hotkey
vod-client mark [message]  same as the marker hotkey, with an optional label
vod-client section         same as the section hotkey
vod-client status
vod-client config          print the config file location and contents
```

`clip`, `mark` and `section` send the action to the running instance over a private local socket.

### Wayland

Wayland does not let applications grab keys globally, so the built-in hotkeys only work on X11,
Windows and macOS. On GNOME, KDE, Hyprland and so on, bind the commands to shortcuts in your
desktop's keyboard settings instead, e.g. `Alt+F10 → vod-client clip`.

## Configuration

The config lives in `~/.config/vod-client/config.json` (or `%AppData%\vod-client` /
`~/Library/Application Support/vod-client`). It is created on the first run.

| Key                     | Default                    | Notes                                                       |
|-------------------------|----------------------------|-------------------------------------------------------------|
| `server_url`            | `http://localhost:8080`    | Backend base URL                                            |
| `rtmp_url`              | `rtmp://<server host>:1935/live` | Override if ingest is on a different host or port     |
| `google_client_id`      |                            | The backend's `GOOGLE_CLIENT_ID`                            |
| `oauth_redirect_port`   | `53682`                    | Must match the registered redirect URI                      |
| `clip_seconds`          | `30`                       | Up to 180, the backend's limit                              |
| `hotkeys.*`             | see above                  | e.g. `ctrl+shift+m`; `""` disables one                      |
| `obs.path`              | auto-detected              | Path to the OBS executable                                  |
| `obs.websocket_port`    | `4455`                     |                                                             |
| `obs.websocket_password`| random                     | Written into OBS's obs-websocket settings                   |
| `obs.auto_install`      | `true`                     | Same as passing `--no-install` when false                   |
| `obs.auto_start_stream` | `true`                     | Also restarts the stream if it drops                        |
| `obs.close_on_exit`     | `true`                     | Stop streaming and quit OBS on exit, if the client started it |

The refresh token is kept in `session.json` next to the config, readable only by you.

### How OBS is managed

- **Install:** Flatpak (user level, no root) when available. Otherwise apt, dnf, pacman or zypper
  through sudo/pkexec. winget on Windows, Homebrew on macOS.
- **Websocket:** before launching OBS, the client enables obs-websocket (OBS 30+) with its own
  password. If OBS is already running, the client attaches to it and leaves it running on exit. In
  that case obs-websocket must already be enabled with the configured port and password.
- **Scene:** if the current scene is empty, a screen capture source is added so a fresh install
  doesn't stream a black screen. Scenes you have set up are left alone.

## Tests

```bash
go test -race ./...
```

The tests run against an in-memory fake of the backend (`internal/api/apitest`) and a fake
obs-websocket server (`internal/obs/obstest`). `TestRegisterX11` grabs a real key on an X server
and is skipped without `DISPLAY`. To run it, use `xvfb-run go test ./internal/hotkey/`, with
`xdotool` installed if you also want it to press the key.
