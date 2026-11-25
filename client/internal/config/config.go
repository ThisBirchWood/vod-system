// Package config loads and persists the vod-client configuration and session.
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
)

// MaxClipSeconds mirrors the backend's CLIP_MAX_LENGTH; longer clips are rejected server side.
const MaxClipSeconds = 180

// Config is the user-editable configuration, stored as JSON.
type Config struct {
	// ServerURL is the backend base URL, e.g. https://vod.example.com or http://localhost:8080.
	ServerURL string `json:"server_url"`
	// RTMPURL is the ingest URL OBS streams to. Derived from ServerURL when empty.
	RTMPURL string `json:"rtmp_url,omitempty"`
	// GoogleClientID is the OAuth web client ID the backend verifies ID tokens against.
	GoogleClientID string `json:"google_client_id"`
	// OAuthRedirectPort is the loopback port for the login callback. http://localhost:<port>/callback
	// must be registered as an authorised redirect URI on the Google OAuth client.
	OAuthRedirectPort int `json:"oauth_redirect_port"`
	// ClipSeconds is how far back a clip reaches, like ShadowPlay's instant replay length.
	ClipSeconds float64 `json:"clip_seconds"`

	Hotkeys Hotkeys `json:"hotkeys"`
	OBS     OBS     `json:"obs"`
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
	AutoInstall       bool   `json:"auto_install"`
	AutoStartStream   bool   `json:"auto_start_stream"`
	// CloseOnExit stops the stream and quits OBS on exit, if vod-client launched it.
	CloseOnExit bool `json:"close_on_exit"`
}

// Default returns a configuration with sensible defaults and a fresh websocket password.
func Default() Config {
	return Config{
		ServerURL:         "http://localhost:8080",
		OAuthRedirectPort: 53682,
		ClipSeconds:       30,
		Hotkeys: Hotkeys{
			Clip:    "alt+f10",
			Section: "alt+f9",
			Marker:  "alt+f11",
		},
		OBS: OBS{
			WebsocketPort:     4455,
			WebsocketPassword: randomHex(16),
			AutoInstall:       true,
			AutoStartStream:   true,
			CloseOnExit:       true,
		},
	}
}

// Validate reports the first problem with the configuration, if any.
func (c Config) Validate() error {
	u, err := url.Parse(c.ServerURL)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return fmt.Errorf("server_url %q must be an absolute http(s) URL", c.ServerURL)
	}
	if c.RTMPURL != "" {
		r, err := url.Parse(c.RTMPURL)
		if err != nil || (r.Scheme != "rtmp" && r.Scheme != "rtmps") || r.Host == "" {
			return fmt.Errorf("rtmp_url %q must be an rtmp:// or rtmps:// URL", c.RTMPURL)
		}
	}
	if c.ClipSeconds <= 0 || c.ClipSeconds > MaxClipSeconds {
		return fmt.Errorf("clip_seconds must be between 0 and %d", MaxClipSeconds)
	}
	if !validPort(c.OAuthRedirectPort) {
		return fmt.Errorf("oauth_redirect_port %d is not a valid port", c.OAuthRedirectPort)
	}
	if !validPort(c.OBS.WebsocketPort) {
		return fmt.Errorf("obs.websocket_port %d is not a valid port", c.OBS.WebsocketPort)
	}
	if c.OBS.WebsocketPassword == "" {
		return errors.New("obs.websocket_password must not be empty")
	}
	return nil
}

// IngestURL returns the RTMP URL OBS should stream to. Without an explicit rtmp_url it assumes the
// nginx-rtmp sidecar runs on the backend's host at the default port and the "live" application.
func (c Config) IngestURL() (string, error) {
	if c.RTMPURL != "" {
		return c.RTMPURL, nil
	}
	u, err := url.Parse(c.ServerURL)
	if err != nil || u.Hostname() == "" {
		return "", fmt.Errorf("cannot derive rtmp_url from server_url %q", c.ServerURL)
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

// Dir returns the directory holding the config and session files.
func Dir() (string, error) {
	base, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(base, "vod-client"), nil
}

// Load reads the config at path. If it does not exist, a default config is written there first,
// so the generated websocket password stays stable across runs. The bool reports creation.
func Load(path string) (Config, bool, error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		cfg := Default()
		if err := Save(path, cfg); err != nil {
			return Config{}, false, err
		}
		return cfg, true, nil
	}
	if err != nil {
		return Config{}, false, err
	}

	// Start from defaults so fields missing from an older file keep sane values.
	cfg := Default()
	if err := json.Unmarshal(data, &cfg); err != nil {
		return Config{}, false, fmt.Errorf("parse %s: %w", path, err)
	}
	return cfg, false, nil
}

// Save writes cfg to path. It holds the websocket password, so it is only readable by the user.
func Save(path string, cfg Config) error {
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return writePrivate(path, append(data, '\n'))
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
