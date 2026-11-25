package config

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestSessionStore(t *testing.T) {
	path := filepath.Join(t.TempDir(), "session.json")
	store := NewSessionStore(path)

	sess, err := store.Load()
	if err != nil || sess != (Session{}) {
		t.Fatalf("missing session: got %+v, %v; want zero, nil", sess, err)
	}

	want := Session{ServerURL: "https://vod.example.com", RefreshToken: "rt-1"}
	if err := store.Save(want); err != nil {
		t.Fatal(err)
	}
	if got, _ := store.Load(); got != want {
		t.Errorf("got %+v, want %+v", got, want)
	}
	if runtime.GOOS != "windows" {
		info, _ := os.Stat(path)
		if perm := info.Mode().Perm(); perm != 0o600 {
			t.Errorf("session permissions %o, want 600", perm)
		}
	}

	if err := store.Clear(); err != nil {
		t.Fatal(err)
	}
	if got, _ := store.Load(); got != (Session{}) {
		t.Errorf("after clear got %+v", got)
	}
	if err := store.Clear(); err != nil {
		t.Errorf("clearing twice should be fine: %v", err)
	}
}

func TestSessionStoreCorruptFileIsEmpty(t *testing.T) {
	path := filepath.Join(t.TempDir(), "session.json")
	os.WriteFile(path, []byte("garbage"), 0o600)
	sess, err := NewSessionStore(path).Load()
	if err != nil || sess != (Session{}) {
		t.Fatalf("got %+v, %v; want zero session and no error", sess, err)
	}
}
