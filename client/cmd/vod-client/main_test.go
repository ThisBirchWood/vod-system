package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestOpenLogAppendsAndRotates(t *testing.T) {
	path := filepath.Join(t.TempDir(), "logs", "vod-client.log")

	w := openLog(path)
	w.Write([]byte("first\n"))
	w = openLog(path)
	w.Write([]byte("second\n"))
	data, _ := os.ReadFile(path)
	if string(data) != "first\nsecond\n" {
		t.Errorf("log = %q, want both runs appended", data)
	}

	os.WriteFile(path, []byte(strings.Repeat("x", maxLogSize+1)), 0o600)
	w = openLog(path)
	w.Write([]byte("fresh\n"))
	if data, _ := os.ReadFile(path); string(data) != "fresh\n" {
		t.Errorf("rotated log = %q", data[:min(len(data), 20)])
	}
	if info, err := os.Stat(path + ".1"); err != nil || info.Size() <= maxLogSize {
		t.Error("the previous log should be kept as .1")
	}
}
