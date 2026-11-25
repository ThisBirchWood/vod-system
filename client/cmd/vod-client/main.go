// Command vod-client is the VoD system's desktop app. It keeps OBS streaming to the VoD server in
// the background and saves clips, sections and markers with global hotkeys, in the style of
// NVIDIA ShadowPlay.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net/url"
	"os"
	"path/filepath"
	"time"

	"fyne.io/fyne/v2"
	fyneapp "fyne.io/fyne/v2/app"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/app"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/auth"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/config"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/control"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/desktop"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/hotkey"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/notify"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/obs"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/ui"
)

func main() {
	hidden := flag.Bool("hidden", false, "start in the system tray (used when starting at login)")
	flag.Parse()

	dir, err := config.Dir()
	if err != nil {
		log.Fatalf("no config directory: %v", err)
	}
	logOut := openLog(filepath.Join(dir, "vod-client.log"))
	logger := log.New(logOut, "", log.LstdFlags)
	logger.Printf("VoD Client %s starting", config.Version)

	// One instance at a time: a second launch just brings the first one's window forward.
	socket, err := control.SocketPath()
	if err != nil {
		logger.Fatalf("control socket: %v", err)
	}
	single, err := control.Listen(socket)
	if errors.Is(err, control.ErrAlreadyRunning) {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if _, err := control.Send(ctx, socket, control.Request{Action: control.ActionShow}); err != nil {
			logger.Printf("could not reach the running instance: %v", err)
		}
		return
	}
	if err != nil {
		logger.Printf("single-instance check unavailable: %v", err)
	}

	a := fyneapp.NewWithID(config.AppID)

	store := config.Store{Path: filepath.Join(dir, "config.json")}
	cfg, err := store.Load()
	if err != nil {
		logger.Printf("load config: %v; using defaults", err)
		cfg = config.Default()
	}

	var autostart func(bool) error
	if env, err := desktop.HostEnv(); err != nil {
		logger.Printf("desktop integration unavailable: %v", err)
	} else {
		if err := env.InstallMenuEntry(ui.IconPNG()); err != nil {
			logger.Printf("menu entry: %v", err)
		}
		// Re-sync every launch: the app (or AppImage) may have moved since the entry was written.
		if err := env.SetAutostart(cfg.StartOnLogin); err != nil {
			logger.Printf("autostart: %v", err)
		}
		autostart = env.SetAutostart
	}

	ctl := app.NewController(cfg, app.Deps{
		SaveConfig: store.Save,
		Sessions:   config.NewSessionStore(filepath.Join(dir, "session.json")),
		Login: func(ctx context.Context, clientID string) (string, error) {
			return auth.GoogleLogin{
				ClientID: clientID,
				Port:     auth.DefaultPort,
				OpenBrowser: func(raw string) error {
					u, err := url.Parse(raw)
					if err != nil {
						return err
					}
					return a.OpenURL(u)
				},
			}.IDToken(ctx)
		},
		System:    obs.HostSystem(logOut),
		Hotkeys:   hotkey.NewManager(config.AppID),
		Notifier:  notify.Func(func(title, body string) { a.SendNotification(fyne.NewNotification(title, body)) }),
		Autostart: autostart,
		Logf:      logger.Printf,
	})

	window := ui.New(a, ctl, ui.Options{TrayAvailable: desktop.TrayAvailable(), StartHidden: *hidden})

	if single != nil {
		defer single.Close()
		go single.Serve(context.Background(), func(_ context.Context, req control.Request) (string, error) {
			if req.Action != control.ActionShow {
				return "", fmt.Errorf("unknown action %q", req.Action)
			}
			fyne.Do(window.ShowWindow)
			return "shown", nil
		})
	}

	a.Lifecycle().SetOnStopped(func() {
		logger.Printf("shutting down")
		ctl.Shutdown()
	})
	ctl.Start()
	window.Run()
}

// maxLogSize is when the log is rotated, keeping one previous file.
const maxLogSize = 1 << 20

// openLog opens the log file for appending, rotating it when large. Users can send it along when
// something goes wrong. If it can't be opened, logs go to stderr.
func openLog(path string) io.Writer {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return os.Stderr
	}
	if info, err := os.Stat(path); err == nil && info.Size() > maxLogSize {
		os.Rename(path, path+".1")
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return os.Stderr
	}
	return io.MultiWriter(f, os.Stderr)
}
