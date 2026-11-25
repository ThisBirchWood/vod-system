package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/config"
)

func TestUnknownCommand(t *testing.T) {
	var out, errOut bytes.Buffer
	if code := run([]string{"explode"}, &out, &errOut); code != 2 {
		t.Errorf("exit = %d, want 2", code)
	}
	if !strings.Contains(errOut.String(), "unknown command") {
		t.Errorf("stderr = %q", errOut.String())
	}
}

func TestHelp(t *testing.T) {
	var out, errOut bytes.Buffer
	if code := run([]string{"help"}, &out, &errOut); code != 0 {
		t.Errorf("exit = %d", code)
	}
	for _, cmd := range []string{"run", "login", "clip", "mark", "section", "status"} {
		if !strings.Contains(out.String(), cmd) {
			t.Errorf("usage missing %q", cmd)
		}
	}
}

func TestConfigFlagsArePersisted(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	var out, errOut bytes.Buffer
	code := run([]string{"config", "--config", path, "--server", "https://vod.example.com/", "--google-client-id", "id-1"}, &out, &errOut)
	if code != 0 {
		t.Fatalf("exit = %d: %s", code, errOut.String())
	}
	cfg, _, err := config.Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.ServerURL != "https://vod.example.com" || cfg.GoogleClientID != "id-1" {
		t.Errorf("config = %+v", cfg)
	}
}

func TestInvalidServerIsNotPersisted(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	var out, errOut bytes.Buffer
	if code := run([]string{"config", "--config", path, "--server", "not a url"}, &out, &errOut); code != 1 {
		t.Fatalf("exit = %d, want 1", code)
	}
	data, _ := os.ReadFile(path)
	if strings.Contains(string(data), "not a url") {
		t.Error("an invalid server URL must not be saved")
	}
}

func TestControlCommandWithoutInstance(t *testing.T) {
	t.Setenv("XDG_RUNTIME_DIR", t.TempDir())
	var out, errOut bytes.Buffer
	if code := run([]string{"clip"}, &out, &errOut); code != 1 {
		t.Errorf("exit = %d, want 1", code)
	}
	if !strings.Contains(errOut.String(), "not running") {
		t.Errorf("stderr = %q", errOut.String())
	}
}
