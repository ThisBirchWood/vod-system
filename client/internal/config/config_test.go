package config

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestDefaultIsValid(t *testing.T) {
	cfg := Default()
	if err := cfg.Validate(); err != nil {
		t.Fatalf("default config invalid: %v", err)
	}
	if len(cfg.OBS.WebsocketPassword) != 32 {
		t.Errorf("websocket password %q: want 32 hex chars", cfg.OBS.WebsocketPassword)
	}
	if Default().OBS.WebsocketPassword == cfg.OBS.WebsocketPassword {
		t.Error("each default config should get a fresh websocket password")
	}
}

func TestValidate(t *testing.T) {
	tests := []struct {
		name    string
		mutate  func(*Config)
		wantErr string
	}{
		{"relative server url", func(c *Config) { c.ServerURL = "localhost:8080" }, "server_url"},
		{"non-http server url", func(c *Config) { c.ServerURL = "ftp://example.com" }, "server_url"},
		{"http rtmp url", func(c *Config) { c.RTMPURL = "http://example.com/live" }, "rtmp_url"},
		{"rtmps allowed", func(c *Config) { c.RTMPURL = "rtmps://example.com/live" }, ""},
		{"zero clip", func(c *Config) { c.ClipSeconds = 0 }, "clip_seconds"},
		{"clip over backend max", func(c *Config) { c.ClipSeconds = MaxClipSeconds + 1 }, "clip_seconds"},
		{"clip at backend max", func(c *Config) { c.ClipSeconds = MaxClipSeconds }, ""},
		{"bad oauth port", func(c *Config) { c.OAuthRedirectPort = 70000 }, "oauth_redirect_port"},
		{"bad websocket port", func(c *Config) { c.OBS.WebsocketPort = 0 }, "websocket_port"},
		{"empty websocket password", func(c *Config) { c.OBS.WebsocketPassword = "" }, "websocket_password"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			cfg := Default()
			tt.mutate(&cfg)
			err := cfg.Validate()
			if tt.wantErr == "" {
				if err != nil {
					t.Fatalf("unexpected error: %v", err)
				}
				return
			}
			if err == nil || !strings.Contains(err.Error(), tt.wantErr) {
				t.Fatalf("got %v, want error mentioning %q", err, tt.wantErr)
			}
		})
	}
}

func TestIngestURL(t *testing.T) {
	tests := []struct {
		server, rtmp, want string
	}{
		{"http://localhost:8080", "", "rtmp://localhost:1935/live"},
		{"https://vod.example.com", "", "rtmp://vod.example.com:1935/live"},
		{"http://[::1]:8080", "", "rtmp://[::1]:1935/live"},
		{"https://vod.example.com", "rtmp://ingest.example.com:2935/app", "rtmp://ingest.example.com:2935/app"},
	}
	for _, tt := range tests {
		cfg := Config{ServerURL: tt.server, RTMPURL: tt.rtmp}
		got, err := cfg.IngestURL()
		if err != nil {
			t.Fatalf("IngestURL(%q): %v", tt.server, err)
		}
		if got != tt.want {
			t.Errorf("IngestURL(%q, %q) = %q, want %q", tt.server, tt.rtmp, got, tt.want)
		}
	}
}

func TestLoadCreatesDefaultAndIsStable(t *testing.T) {
	path := filepath.Join(t.TempDir(), "nested", "config.json")

	first, created, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if !created {
		t.Error("first load should report the file as created")
	}

	second, created, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if created {
		t.Error("second load should not recreate the file")
	}
	if first.OBS.WebsocketPassword != second.OBS.WebsocketPassword {
		t.Error("websocket password must persist across loads")
	}

	if runtime.GOOS != "windows" {
		info, err := os.Stat(path)
		if err != nil {
			t.Fatal(err)
		}
		if perm := info.Mode().Perm(); perm != 0o600 {
			t.Errorf("config permissions %o, want 600 (it holds a password)", perm)
		}
	}
}

func TestLoadFillsMissingFieldsWithDefaults(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(path, []byte(`{"server_url":"https://vod.example.com","hotkeys":{"clip":"ctrl+f10"}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, _, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.ServerURL != "https://vod.example.com" || cfg.Hotkeys.Clip != "ctrl+f10" {
		t.Errorf("explicit values not kept: %+v", cfg)
	}
	if cfg.ClipSeconds != 30 || cfg.OBS.WebsocketPort != 4455 || !cfg.OBS.AutoStartStream {
		t.Errorf("missing values not defaulted: %+v", cfg)
	}
	if cfg.Hotkeys.Section != "alt+f9" {
		t.Errorf("unset hotkey not defaulted: %q", cfg.Hotkeys.Section)
	}
}

func TestLoadRejectsMalformedJSON(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	os.WriteFile(path, []byte(`{not json`), 0o600)
	if _, _, err := Load(path); err == nil {
		t.Fatal("expected a parse error")
	}
}

func TestSaveRoundTrip(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	cfg := Default()
	cfg.ServerURL = "https://vod.example.com"
	cfg.GoogleClientID = "abc.apps.googleusercontent.com"
	cfg.Hotkeys.Marker = ""
	if err := Save(path, cfg); err != nil {
		t.Fatal(err)
	}
	got, _, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if got != cfg {
		t.Errorf("round trip mismatch:\n got %+v\nwant %+v", got, cfg)
	}
}
