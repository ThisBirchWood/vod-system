// Package config loads and persists the vod-client settings and session.
package config

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/hotkey"
)

// Set at build time so users never have to type a server address:
//
//	go build -ldflags "-X gitea.defaoite.dev/dylan/vod-system/client/internal/config.DefaultServerURL=https://vod.example.com"
var (
	DefaultServerURL = ""
	Version          = "dev"
)

// AppID identifies the app to the OS: notifications, desktop entries and the shortcuts portal.
const AppID = "dev.defaoite.VodClient"

// AppName is the user-facing name.
const AppName = "VoD Client"

// MaxClipSeconds mirrors the backend's CLIP_MAX_LENGTH; longer clips are rejected server side.
const MaxClipSeconds = 180

// Config holds the user's settings. Everything here is editable from the Settings window; the
// JSON file is an implementation detail.
type Config struct {
	// ServerURL is the backend base URL. Defaults to DefaultServerURL.
	ServerURL string `json:"server_url"`
	// ClipSeconds is how far back a clip reaches, like ShadowPlay's instant replay length.
	ClipSeconds float64 `json:"clip_seconds"`
	Hotkeys     Hotkeys `json:"hotkeys"`
	// StartOnLogin launches the client into the tray when the user logs in to their computer.
	StartOnLogin bool `json:"start_on_login"`
	OBS          OBS  `json:"obs"`
}

// Hotkeys maps each action to a key combination such as "alt+f10". Empty disables the hotkey.
type Hotkeys struct {
	Clip    string `json:"clip"`
	Marker  string `json:"marker"`
	Section string `json:"section"`
}

// OBS holds settings for the managed OBS instance.
type OBS struct {
	// Path overrides OBS auto-detection.
	Path              string `json:"path,omitempty"`
	WebsocketPort     int    `json:"websocket_port"`
	WebsocketPassword string `json:"websocket_password"`
	// AutoStartStream goes live as soon as OBS is ready, and keeps the stream up if it drops.
	AutoStartStream bool `json:"auto_start_stream"`
	// CloseOnExit stops the stream and quits OBS on exit, if vod-client launched it.
	CloseOnExit bool `json:"close_on_exit"`
}

// Default returns a configuration with sensible defaults and a fresh websocket password.
func Default() Config {
	return Config{
		ServerURL:    DefaultServerURL,
		ClipSeconds:  30,
		StartOnLogin: true,
		Hotkeys:      DefaultHotkeys(runtime.GOOS),
		OBS: OBS{
			WebsocketPort:     4455,
			WebsocketPassword: randomHex(16),
			AutoStartStream:   true,
			CloseOnExit:       true,
		},
	}
}

// NormalizeServerURL turns what a user might type ("vod.example.com/") into a base URL.
func NormalizeServerURL(s string) (string, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return "", errors.New("enter your VoD server's address")
	}
	if !strings.Contains(s, "://") {
		s = "https://" + s
	}
	u, err := url.Parse(s)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return "", fmt.Errorf("%q is not a valid server address", s)
	}
	return strings.TrimRight(u.Scheme+"://"+u.Host+u.Path, "/"), nil
}

// Validate reports the first problem with the configuration, if any. An empty server URL is
// allowed: it means the user has not chosen one yet.
func (c Config) Validate() error {
	if c.ServerURL != "" {
		if _, err := NormalizeServerURL(c.ServerURL); err != nil {
			return fmt.Errorf("server_url: %w", err)
		}
	}
	if c.ClipSeconds <= 0 || c.ClipSeconds > MaxClipSeconds {
		return fmt.Errorf("clip_seconds must be between 0 and %d", MaxClipSeconds)
	}
	if !validPort(c.OBS.WebsocketPort) {
		return fmt.Errorf("obs.websocket_port %d is not a valid port", c.OBS.WebsocketPort)
	}
	if c.OBS.WebsocketPassword == "" {
		return errors.New("obs.websocket_password must not be empty")
	}
	return nil
}

// IngestURL returns the RTMP URL OBS should stream to: the one the server advertises, or else
// the nginx-rtmp sidecar's default port and application on the server's host.
func IngestURL(serverURL, advertised string) (string, error) {
	if advertised != "" {
		return advertised, nil
	}
	u, err := url.Parse(serverURL)
	if err != nil || u.Hostname() == "" {
		return "", fmt.Errorf("cannot derive an RTMP URL from %q", serverURL)
	}
	return "rtmp://" + net.JoinHostPort(u.Hostname(), "1935") + "/live", nil
}

func validPort(p int) bool { return p > 0 && p < 65536 }

func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err) // crypto/rand never fails on supported platforms
	}
	return hex.EncodeToString(b)
}

// Dir returns the directory holding the config, session and log files.
func Dir() (string, error) {
	base, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(base, "vod-client"), nil
}

// Store reads and writes the config file.
type Store struct{ Path string }

// Load reads the config. If it does not exist, defaults are written first so the generated
// websocket password stays stable across runs. An invalid file is replaced by defaults rather
// than locking a non-technical user out of the app.
func (s Store) Load() (Config, error) {
	data, err := os.ReadFile(s.Path)
	if errors.Is(err, os.ErrNotExist) {
		cfg := Default()
		return cfg, s.Save(cfg)
	}
	if err != nil {
		return Config{}, err
	}

	// Start from defaults so fields missing from an older file keep sane values.
	cfg := Default()
	if err := json.Unmarshal(data, &cfg); err != nil || cfg.Validate() != nil {
		cfg = Default()
		return cfg, s.Save(cfg)
	}
	if cfg.ServerURL == "" {
		cfg.ServerURL = DefaultServerURL
	}
	if migrated := MigrateHotkeys(runtime.GOOS, cfg.Hotkeys); migrated != cfg.Hotkeys {
		cfg.Hotkeys = migrated
		return cfg, s.Save(cfg)
	}
	return cfg, nil
}

// DefaultHotkeys are ShadowPlay's Alt+F9/F10 layout, plus F11 for markers. macOS refuses
// Option-only hotkeys, so there they use Ctrl+Option.
func DefaultHotkeys(goos string) Hotkeys {
	mod := "alt+"
	if goos == "darwin" {
		mod = "ctrl+alt+"
	}
	return Hotkeys{Clip: mod + "f10", Section: mod + "f9", Marker: mod + "f11"}
}

// MigrateHotkeys adds Ctrl to hotkeys the platform would refuse, such as Option-only ones saved
// by an older version on macOS, so they keep working instead of silently failing.
func MigrateHotkeys(goos string, h Hotkeys) Hotkeys {
	fix := func(combo string) string {
		b, err := hotkey.Parse(combo)
		if err != nil || hotkey.CheckPlatform(goos, b) == nil {
			return combo
		}
		b.Mods |= hotkey.Ctrl
		return b.String()
	}
	return Hotkeys{Clip: fix(h.Clip), Section: fix(h.Section), Marker: fix(h.Marker)}
}

// Save writes cfg. It holds the websocket password, so it is only readable by the user.
func (s Store) Save(cfg Config) error {
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return writePrivate(s.Path, append(data, '\n'))
}

// writePrivate atomically writes a file readable only by the current user.
func writePrivate(path string, data []byte) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), "."+filepath.Base(path)+".*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if err := tmp.Chmod(0o600); err != nil && !errors.Is(err, errors.ErrUnsupported) {
		// Chmod is a no-op on Windows; anything else is a real failure.
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), path)
}
