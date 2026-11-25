package control

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

// socketPath returns a short path: Unix socket paths are limited to ~104 bytes on macOS.
func socketPath(t *testing.T) string {
	dir, err := os.MkdirTemp("", "vc")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	return filepath.Join(dir, "c.sock")
}

func serve(t *testing.T, path string, h Handler) *Server {
	t.Helper()
	s, err := Listen(path)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	go s.Serve(ctx, h)
	t.Cleanup(func() {
		cancel()
		s.Close()
	})
	return s
}

func TestSendRoundTrip(t *testing.T) {
	path := socketPath(t)
	var got Request
	serve(t, path, func(_ context.Context, req Request) (string, error) {
		got = req
		return "done: " + req.Action, nil
	})

	msg, err := Send(context.Background(), path, Request{Action: "mark", Arg: "boss fight"})
	if err != nil {
		t.Fatal(err)
	}
	if msg != "done: mark" {
		t.Errorf("msg = %q", msg)
	}
	if got.Action != "mark" || got.Arg != "boss fight" {
		t.Errorf("handler got %+v", got)
	}
}

func TestSendHandlerError(t *testing.T) {
	path := socketPath(t)
	serve(t, path, func(context.Context, Request) (string, error) {
		return "", errors.New("not live")
	})
	_, err := Send(context.Background(), path, Request{Action: "clip"})
	if err == nil || err.Error() != "not live" {
		t.Fatalf("got %v, want the handler's error", err)
	}
}

func TestSendNotRunning(t *testing.T) {
	_, err := Send(context.Background(), socketPath(t), Request{Action: "clip"})
	if !errors.Is(err, ErrNotRunning) {
		t.Fatalf("got %v, want ErrNotRunning", err)
	}
}

func TestListenRefusesSecondInstance(t *testing.T) {
	path := socketPath(t)
	serve(t, path, func(context.Context, Request) (string, error) { return "", nil })
	if _, err := Listen(path); err == nil || !strings.Contains(err.Error(), "already running") {
		t.Fatalf("got %v, want already running", err)
	}
}

func TestListenReplacesStaleSocket(t *testing.T) {
	path := socketPath(t)
	os.WriteFile(path, nil, 0o600) // left behind by a crash; nothing listening
	s, err := Listen(path)
	if err != nil {
		t.Fatalf("stale socket should be replaced: %v", err)
	}
	s.Close()
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Error("Close should remove the socket file")
	}
}

func TestSocketIsPrivate(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Unix permissions")
	}
	path := socketPath(t)
	serve(t, path, func(context.Context, Request) (string, error) { return "", nil })
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Errorf("socket permissions %o, want 600", perm)
	}
}

func TestSendTimesOut(t *testing.T) {
	path := socketPath(t)
	serve(t, path, func(ctx context.Context, _ Request) (string, error) {
		time.Sleep(2 * time.Second)
		return "late", nil
	})
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	if _, err := Send(ctx, path, Request{Action: "clip"}); err == nil {
		t.Fatal("expected a timeout")
	}
}

func TestSocketPathPrefersRuntimeDir(t *testing.T) {
	t.Setenv("XDG_RUNTIME_DIR", "/run/user/1000")
	p, err := SocketPath()
	if err != nil {
		t.Fatal(err)
	}
	if p != filepath.Join("/run/user/1000", "vod-client.sock") {
		t.Errorf("path = %s", p)
	}
}
