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

func TestDefaultUsesBuildTimeServer(t *testing.T) {
	old := DefaultServerURL
	t.Cleanup(func() { DefaultServerURL = old })
	DefaultServerURL = "https://vod.example.com"
	if got := Default().ServerURL; got != "https://vod.example.com" {
		t.Errorf("ServerURL = %q", got)
	}
}

func TestNormalizeServerURL(t *testing.T) {
	tests := []struct{ in, want string }{
		{"vod.example.com", "https://vod.example.com"},
		{" https://vod.example.com/ ", "https://vod.example.com"},
		{"http://localhost:8080", "http://localhost:8080"},
		{"https://example.com/vod/", "https://example.com/vod"},
		{"https://vod.example.com/?x=1#y", "https://vod.example.com"},
	}
	for _, tt := range tests {
		got, err := NormalizeServerURL(tt.in)
		if err != nil || got != tt.want {
			t.Errorf("NormalizeServerURL(%q) = %q, %v; want %q", tt.in, got, err, tt.want)
		}
	}
	for _, bad := range []string{"", "   ", "ftp://example.com", "https://"} {
		if got, err := NormalizeServerURL(bad); err == nil {
			t.Errorf("NormalizeServerURL(%q) = %q, want error", bad, got)
		}
	}
}

func TestValidate(t *testing.T) {
	tests := []struct {
		name    string
		mutate  func(*Config)
		wantErr string
	}{
		{"no server yet is fine", func(c *Config) { c.ServerURL = "" }, ""},
		{"bad server", func(c *Config) { c.ServerURL = "ftp://x" }, "server_url"},
		{"zero clip", func(c *Config) { c.ClipSeconds = 0 }, "clip_seconds"},
		{"clip over backend max", func(c *Config) { c.ClipSeconds = MaxClipSeconds + 1 }, "clip_seconds"},
		{"clip at backend max", func(c *Config) { c.ClipSeconds = MaxClipSeconds }, ""},
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
		server, advertised, want string
	}{
		{"http://localhost:8080", "", "rtmp://localhost:1935/live"},
		{"https://vod.example.com", "", "rtmp://vod.example.com:1935/live"},
		{"http://[::1]:8080", "", "rtmp://[::1]:1935/live"},
		{"https://vod.example.com", "rtmp://ingest.example.com:2935/app", "rtmp://ingest.example.com:2935/app"},
	}
	for _, tt := range tests {
		got, err := IngestURL(tt.server, tt.advertised)
		if err != nil || got != tt.want {
			t.Errorf("IngestURL(%q, %q) = %q, %v; want %q", tt.server, tt.advertised, got, err, tt.want)
		}
	}
	if _, err := IngestURL("", ""); err == nil {
		t.Error("no server and nothing advertised should fail")
	}
}

func TestLoadCreatesDefaultAndIsStable(t *testing.T) {
	s := Store{Path: filepath.Join(t.TempDir(), "nested", "config.json")}

	first, err := s.Load()
	if err != nil {
		t.Fatal(err)
	}
	second, err := s.Load()
	if err != nil {
		t.Fatal(err)
	}
	if first.OBS.WebsocketPassword != second.OBS.WebsocketPassword {
		t.Error("websocket password must persist across loads")
	}

	if runtime.GOOS != "windows" {
		info, err := os.Stat(s.Path)
		if err != nil {
			t.Fatal(err)
		}
		if perm := info.Mode().Perm(); perm != 0o600 {
			t.Errorf("config permissions %o, want 600 (it holds a password)", perm)
		}
	}
}

func TestLoadFillsMissingFieldsWithDefaults(t *testing.T) {
	s := Store{Path: filepath.Join(t.TempDir(), "config.json")}
	os.WriteFile(s.Path, []byte(`{"server_url":"https://vod.example.com","hotkeys":{"clip":"ctrl+f10"}}`), 0o600)
	cfg, err := s.Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.ServerURL != "https://vod.example.com" || cfg.Hotkeys.Clip != "ctrl+f10" {
		t.Errorf("explicit values not kept: %+v", cfg)
	}
	if cfg.ClipSeconds != 30 || cfg.OBS.WebsocketPort != 4455 || !cfg.OBS.AutoStartStream {
		t.Errorf("missing values not defaulted: %+v", cfg)
	}
}

func TestLoadReplacesBrokenFile(t *testing.T) {
	for _, content := range []string{`{not json`, `{"clip_seconds": -4}`} {
		s := Store{Path: filepath.Join(t.TempDir(), "config.json")}
		os.WriteFile(s.Path, []byte(content), 0o600)
		cfg, err := s.Load()
		if err != nil {
			t.Fatalf("%s: %v", content, err)
		}
		if cfg.Validate() != nil || cfg.ClipSeconds != 30 {
			t.Errorf("%s: broken config should be reset to defaults, got %+v", content, cfg)
		}
	}
}

func TestSaveRoundTrip(t *testing.T) {
	s := Store{Path: filepath.Join(t.TempDir(), "config.json")}
	cfg := Default()
	cfg.ServerURL = "https://vod.example.com"
	cfg.StartOnLogin = false
	cfg.Hotkeys.Marker = ""
	if err := s.Save(cfg); err != nil {
		t.Fatal(err)
	}
	got, err := s.Load()
	if err != nil {
		t.Fatal(err)
	}
	if got != cfg {
		t.Errorf("round trip mismatch:\n got %+v\nwant %+v", got, cfg)
	}
}

func TestDefaultHotkeys(t *testing.T) {
	if got := DefaultHotkeys("windows"); got != (Hotkeys{Clip: "alt+f10", Section: "alt+f9", Marker: "alt+f11"}) {
		t.Errorf("windows = %+v", got)
	}
	if got := DefaultHotkeys("linux"); got.Clip != "alt+f10" {
		t.Errorf("linux = %+v", got)
	}
	// macOS refuses Option-only hotkeys.
	if got := DefaultHotkeys("darwin"); got != (Hotkeys{Clip: "ctrl+alt+f10", Section: "ctrl+alt+f9", Marker: "ctrl+alt+f11"}) {
		t.Errorf("darwin = %+v", got)
	}
}

func TestMigrateHotkeys(t *testing.T) {
	old := Hotkeys{Clip: "alt+f10", Section: "shift+alt+f9", Marker: "cmd+f11"}
	got := MigrateHotkeys("darwin", old)
	want := Hotkeys{Clip: "ctrl+alt+f10", Section: "ctrl+shift+alt+f9"}
	if got.Clip != want.Clip || got.Section != want.Section {
		t.Errorf("darwin migration = %+v, want %+v", got, want)
	}
	if got.Marker != "cmd+f11" {
		t.Errorf("valid hotkeys must be left exactly as saved, got %q", got.Marker)
	}
	if MigrateHotkeys("linux", old) != old {
		t.Error("other platforms allow Option-only hotkeys")
	}
	empty := Hotkeys{Clip: "", Section: "garbage", Marker: "alt+f11"}
	if got := MigrateHotkeys("darwin", empty); got.Clip != "" || got.Section != "garbage" {
		t.Errorf("disabled or unparsable hotkeys are left alone, got %+v", got)
	}
}
