package app

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/api"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/api/apitest"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/config"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/control"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/hotkey"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/obs"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/obs/obstest"
)

type fakeStreamer struct {
	active   bool
	err      error
	starts   int
	startErr error
}

func (f *fakeStreamer) StreamActive(context.Context) (bool, error) { return f.active, f.err }
func (f *fakeStreamer) StartStream(context.Context) error {
	f.starts++
	return f.startErr
}

func TestEnsureStreaming(t *testing.T) {
	s := &fakeStreamer{active: true}
	if started, err := EnsureStreaming(context.Background(), s); started || err != nil || s.starts != 0 {
		t.Errorf("active stream: started=%v err=%v starts=%d", started, err, s.starts)
	}

	s = &fakeStreamer{}
	if started, err := EnsureStreaming(context.Background(), s); !started || err != nil || s.starts != 1 {
		t.Errorf("stopped stream: started=%v err=%v starts=%d", started, err, s.starts)
	}

	s = &fakeStreamer{err: errors.New("obs gone")}
	if _, err := EnsureStreaming(context.Background(), s); err == nil || s.starts != 0 {
		t.Error("status errors must not trigger a start")
	}
}

func newAPI(t *testing.T, b *apitest.Backend, withSession bool) *api.Client {
	t.Helper()
	store := config.NewSessionStore(filepath.Join(t.TempDir(), "session.json"))
	if withSession {
		store.Save(config.Session{ServerURL: b.URL, RefreshToken: b.IssueRefreshToken()})
	}
	c, err := api.New(b.URL, store)
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func noLog(string, ...any) {}

func TestEnsureSessionResumes(t *testing.T) {
	b := apitest.New()
	defer b.Close()
	login := func(context.Context) (string, error) {
		t.Fatal("must not open the browser when a session exists")
		return "", nil
	}
	u, err := EnsureSession(context.Background(), newAPI(t, b, true), login, noLog)
	if err != nil || u.StreamKey != "key-abc123" {
		t.Fatalf("got %+v, %v", u, err)
	}
}

func TestEnsureSessionLogsInWhenExpired(t *testing.T) {
	b := apitest.New()
	defer b.Close()
	c := newAPI(t, b, true)
	b.RevokeAll()
	b.Set(func(b *apitest.Backend) { b.ValidIDTokens["google-ok"] = true })

	logins := 0
	login := func(context.Context) (string, error) {
		logins++
		return "google-ok", nil
	}
	u, err := EnsureSession(context.Background(), c, login, noLog)
	if err != nil || u.Username != "streamer" {
		t.Fatalf("got %+v, %v", u, err)
	}
	if logins != 1 {
		t.Errorf("logins = %d", logins)
	}
}

func TestEnsureSessionLoginFailure(t *testing.T) {
	b := apitest.New()
	defer b.Close()
	login := func(context.Context) (string, error) { return "", errors.New("user closed the browser") }
	if _, err := EnsureSession(context.Background(), newAPI(t, b, false), login, noLog); err == nil {
		t.Fatal("expected the login error")
	}
}

// fakeReg is a hotkey registration the test presses by hand.
type fakeReg struct {
	pressed      chan struct{}
	unregistered bool
}

func (f *fakeReg) Pressed() <-chan struct{} { return f.pressed }
func (f *fakeReg) Unregister() error {
	f.unregistered = true
	return nil
}

type harness struct {
	backend *apitest.Backend
	obs     *obstest.Server
	runner  *Runner
	notes   *recorder
	socket  string
	mu      sync.Mutex
	hotkeys map[string]*fakeReg
	cancel  context.CancelFunc
	done    chan error
}

func newHarness(t *testing.T, mutate func(*config.Config)) *harness {
	t.Helper()
	h := &harness{backend: apitest.New(), obs: obstest.New("pw"), notes: &recorder{}, hotkeys: map[string]*fakeReg{}}
	t.Cleanup(h.backend.Close)
	t.Cleanup(h.obs.Close)

	dir, err := os.MkdirTemp("", "vc")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	h.socket = filepath.Join(dir, "c.sock")

	cfg := config.Default()
	cfg.ServerURL = h.backend.URL
	cfg.RTMPURL = "rtmp://ingest.test:1935/live"
	cfg.OBS.WebsocketPort = h.obs.Port()
	cfg.OBS.WebsocketPassword = "pw"
	if mutate != nil {
		mutate(&cfg)
	}

	h.runner = &Runner{
		Config:     cfg,
		API:        newAPI(t, h.backend, true),
		Login:      func(context.Context) (string, error) { return "", errors.New("no browser in tests") },
		Notifier:   h.notes,
		SocketPath: h.socket,
		NoInstall:  true,
		Logf:       func(f string, a ...any) { t.Logf(f, a...) },
		KeepAlive:  50 * time.Millisecond,
		RegisterHotkey: func(b hotkey.Binding) (hotkey.Registration, error) {
			h.mu.Lock()
			defer h.mu.Unlock()
			r := &fakeReg{pressed: make(chan struct{}, 1)}
			h.hotkeys[b.String()] = r
			return r, nil
		},
	}
	return h
}

func (h *harness) start(t *testing.T) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	h.cancel = cancel
	h.done = make(chan error, 1)
	go func() { h.done <- h.runner.Run(ctx) }()
	waitFor(t, "client ready", func() bool { return h.notes.has("VoD Client ready") })
}

func (h *harness) stop(t *testing.T) error {
	t.Helper()
	h.cancel()
	select {
	case err := <-h.done:
		return err
	case <-time.After(10 * time.Second):
		t.Fatal("Run did not return after cancel")
		return nil
	}
}

func (h *harness) press(t *testing.T, combo string) {
	t.Helper()
	h.mu.Lock()
	r, ok := h.hotkeys[combo]
	h.mu.Unlock()
	if !ok {
		t.Fatalf("hotkey %s not registered", combo)
	}
	r.pressed <- struct{}{}
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

func TestRunEndToEnd(t *testing.T) {
	h := newHarness(t, nil)
	h.start(t)

	// OBS was pointed at the VoD system with the user's stream key and started streaming.
	svc := h.obs.StreamService()
	settings, _ := svc["streamServiceSettings"].(map[string]any)
	if svc["streamServiceType"] != "rtmp_custom" || settings["server"] != "rtmp://ingest.test:1935/live" || settings["key"] != "key-abc123" {
		t.Errorf("stream service = %v", svc)
	}
	if !h.obs.Streaming() {
		t.Error("OBS should be streaming")
	}
	if len(h.obs.RequestsOf("CreateInput")) != 1 {
		t.Error("an empty scene should get a capture source")
	}

	// Default ShadowPlay-style hotkeys are registered.
	for _, combo := range []string{"alt+f10", "alt+f9", "alt+f11"} {
		h.mu.Lock()
		_, ok := h.hotkeys[combo]
		h.mu.Unlock()
		if !ok {
			t.Errorf("hotkey %s not registered", combo)
		}
	}

	// Clip hotkey.
	h.press(t, "alt+f10")
	waitFor(t, "clip request", func() bool { return len(h.backend.CallsTo("/api/v1/media/clip")) == 1 })
	if d := h.backend.CallsTo("/api/v1/media/clip")[0].Body["duration"]; d != 30.0 {
		t.Errorf("clip duration = %v", d)
	}

	// Marker hotkey.
	h.press(t, "alt+f11")
	waitFor(t, "marker", func() bool { return len(h.backend.CallsTo("/api/v1/markers")) == 1 })

	// Section toggle over the control socket, the Wayland path.
	ctx := context.Background()
	if msg, err := control.Send(ctx, h.socket, control.Request{Action: ActionSection}); err != nil || msg != "Section started" {
		t.Fatalf("section start: %q, %v", msg, err)
	}
	if msg, err := control.Send(ctx, h.socket, control.Request{Action: ActionSection}); err != nil || msg != "Saving section" {
		t.Fatalf("section end: %q, %v", msg, err)
	}
	saves := h.backend.CallsTo("/api/v1/media/save/markers")
	if len(saves) != 1 || saves[0].Body["startMarkerId"] != 2.0 || saves[0].Body["endMarkerId"] != 3.0 {
		t.Errorf("save calls = %v", saves)
	}

	// Errors come back through the socket.
	h.backend.Set(func(b *apitest.Backend) { b.Streaming = false })
	if _, err := control.Send(ctx, h.socket, control.Request{Action: ActionMarker, Arg: "x"}); err == nil ||
		!strings.Contains(err.Error(), "streaming") {
		t.Errorf("marker while offline: %v", err)
	}

	if err := h.stop(t); err != nil {
		t.Fatalf("Run: %v", err)
	}
	// vod-client attached to an OBS the user started, so it leaves the stream running.
	if len(h.obs.RequestsOf("StopStream")) != 0 {
		t.Error("must not stop the stream of an OBS it did not launch")
	}
	h.mu.Lock()
	for combo, r := range h.hotkeys {
		if !r.unregistered {
			t.Errorf("hotkey %s not unregistered", combo)
		}
	}
	h.mu.Unlock()
	if _, err := control.Send(ctx, h.socket, control.Request{Action: ActionStatus}); !errors.Is(err, control.ErrNotRunning) {
		t.Errorf("control socket should be gone after exit: %v", err)
	}
}

func TestRunRestartsDroppedStream(t *testing.T) {
	h := newHarness(t, nil)
	h.start(t)

	h.obs.SetStreaming(false)
	waitFor(t, "stream restart notification", func() bool { return h.notes.has("Stream restarted") })
	if !h.obs.Streaming() {
		t.Error("OBS should be streaming again")
	}
	h.stop(t)
}

func TestRunReconnectsToOBS(t *testing.T) {
	h := newHarness(t, nil)
	h.start(t)
	before := len(h.obs.RequestsOf("GetStreamStatus"))

	h.obs.DropConnections()
	h.obs.SetStreaming(false)
	// The keep-alive keeps working over a fresh connection.
	waitFor(t, "keep-alive after reconnect", func() bool {
		return len(h.obs.RequestsOf("GetStreamStatus")) > before && h.obs.Streaming()
	})
	h.stop(t)
}

func TestRunWithoutAutoStart(t *testing.T) {
	h := newHarness(t, func(c *config.Config) { c.OBS.AutoStartStream = false })
	h.start(t)
	time.Sleep(150 * time.Millisecond) // a few keep-alive ticks
	if h.obs.Streaming() {
		t.Error("stream must not start when auto_start_stream is off")
	}
	h.stop(t)
}

func TestRunSkipsDisabledAndInvalidHotkeys(t *testing.T) {
	h := newHarness(t, func(c *config.Config) {
		c.Hotkeys.Marker = ""
		c.Hotkeys.Section = "nonsense"
	})
	h.start(t)
	h.mu.Lock()
	n := len(h.hotkeys)
	h.mu.Unlock()
	if n != 1 {
		t.Errorf("registered %d hotkeys, want only the clip one", n)
	}
	h.stop(t)
}

func TestRunWithoutHotkeySupport(t *testing.T) {
	h := newHarness(t, nil)
	h.runner.RegisterHotkey = func(b hotkey.Binding) (hotkey.Registration, error) {
		return nil, fmt.Errorf("register %s: %w", b, hotkey.ErrUnavailable)
	}
	h.start(t)
	if !h.notes.has("VoD Client ready: Streaming. Use `vod-client clip`") {
		t.Errorf("ready message should point at the commands: %v", h.notes.all())
	}
	if _, err := control.Send(context.Background(), h.socket, control.Request{Action: ActionClip}); err != nil {
		t.Errorf("control socket should still work: %v", err)
	}
	h.stop(t)
}

func TestRunWrongOBSPassword(t *testing.T) {
	h := newHarness(t, func(c *config.Config) { c.OBS.WebsocketPassword = "stale" })
	err := h.runner.Run(context.Background())
	if !errors.Is(err, obs.ErrAuthFailed) || !strings.Contains(err.Error(), "websocket_password") {
		t.Fatalf("got %v, want a hint about the websocket password", err)
	}
}

func TestRunRefusesSecondInstance(t *testing.T) {
	h := newHarness(t, nil)
	h.start(t)
	defer h.stop(t)

	second := *h.runner
	err := second.Run(context.Background())
	if err == nil || !strings.Contains(err.Error(), "already running") {
		t.Fatalf("got %v", err)
	}
}

func TestRunNeedsLoginWithoutSession(t *testing.T) {
	h := newHarness(t, nil)
	h.runner.API = newAPI(t, h.backend, false)
	err := h.runner.Run(context.Background())
	if err == nil || !strings.Contains(err.Error(), "no browser") {
		t.Fatalf("got %v, want the login error", err)
	}
	if len(h.obs.Requests()) != 0 {
		t.Error("OBS must not be touched before login succeeds")
	}
}
