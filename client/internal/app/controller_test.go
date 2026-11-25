package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/api"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/api/apitest"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/config"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/hotkey"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/obs"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/obs/obstest"
)

// TestMain lets the test binary double as OBS: launched with FAKE_OBS_PORT set, it serves a
// fake obs-websocket on that port instead of running tests.
func TestMain(m *testing.M) {
	if port := os.Getenv("FAKE_OBS_PORT"); port != "" {
		os.Exit(runFakeOBS(port))
	}
	os.Exit(m.Run())
}

func runFakeOBS(port string) int {
	// A real OBS reads its websocket settings from the config vod-client wrote; check it did.
	data, err := os.ReadFile(os.Getenv("FAKE_OBS_CONFIG"))
	if err != nil {
		return 3
	}
	var settings struct {
		Password string `json:"server_password"`
		Enabled  bool   `json:"server_enabled"`
	}
	if json.Unmarshal(data, &settings) != nil || !settings.Enabled {
		return 4
	}
	if _, err := obstest.Listen("127.0.0.1:"+port, settings.Password); err != nil {
		return 5
	}
	quit := os.Getenv("FAKE_OBS_QUIT_FILE")
	for {
		if _, err := os.Stat(quit); err == nil {
			return 0 // the "user" closed OBS
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// fakeHotkeys is a hotkey.Manager the test presses by hand.
type fakeHotkeys struct {
	mu         sync.Mutex
	pressed    chan string
	binds      [][]hotkey.Shortcut
	err        error
	appManaged bool
	closed     bool
}

func newFakeHotkeys() *fakeHotkeys {
	return &fakeHotkeys{pressed: make(chan string, 4), appManaged: true}
}

func (f *fakeHotkeys) Bind(_ context.Context, s []hotkey.Shortcut) (map[string]string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.binds = append(f.binds, s)
	if f.err != nil {
		return nil, f.err
	}
	out := map[string]string{}
	for _, sc := range s {
		out[sc.ID] = sc.Binding.Display()
	}
	return out, nil
}
func (f *fakeHotkeys) Pressed() <-chan string { return f.pressed }
func (f *fakeHotkeys) AppManaged() bool       { return f.appManaged }
func (f *fakeHotkeys) Close() error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.closed = true
	return nil
}
func (f *fakeHotkeys) bindCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.binds)
}

// fakeOS is an obs.System where OBS is (or becomes, once installed) available at a path.
type fakeOS struct {
	mu        sync.Mutex
	home      string
	installed bool
	running   bool // what pgrep reports
	installs  []string
}

func (f *fakeOS) system() obs.System {
	return obs.System{
		GOOS: "linux", Home: f.home,
		Getenv: func(string) string { return "" },
		LookPath: func(name string) (string, error) {
			f.mu.Lock()
			defer f.mu.Unlock()
			switch {
			case name == "flatpak":
				return "/usr/bin/flatpak", nil
			case name == "obs" && f.installed:
				return os.Args[0], nil // the test binary plays OBS; see TestMain
			}
			return "", exec.ErrNotFound
		},
		Exists: func(string) bool {
			f.mu.Lock()
			defer f.mu.Unlock()
			return f.installed
		},
		Run: func(_ context.Context, name string, args ...string) error {
			f.mu.Lock()
			defer f.mu.Unlock()
			f.installs = append(f.installs, name+" "+strings.Join(args, " "))
			f.installed = true
			return nil
		},
		Output: func(_ context.Context, name string, args ...string) ([]byte, error) {
			f.mu.Lock()
			defer f.mu.Unlock()
			if name == "pgrep" && f.running {
				return []byte("123"), nil
			}
			return nil, errors.New("exit status 1")
		},
	}
}

type harness struct {
	t         *testing.T
	backend   *apitest.Backend
	obs       *obstest.Server // nil when OBS is launched as a process
	os        *fakeOS
	hotkeys   *fakeHotkeys
	sessions  *config.SessionStore
	c         *Controller
	saved     []config.Config
	autostart []bool
	logins    []string
	mu        sync.Mutex
}

type option func(*harness, *config.Config)

func withoutOBSRunning(h *harness, cfg *config.Config) {
	cfg.OBS.WebsocketPort = freePort(h.t)
}

func newHarness(t *testing.T, signedIn bool, opts ...option) *harness {
	t.Helper()
	h := &harness{t: t, backend: apitest.New(), hotkeys: newFakeHotkeys(), os: &fakeOS{home: t.TempDir()}}
	t.Cleanup(h.backend.Close)
	h.backend.Set(func(b *apitest.Backend) {
		b.ValidIDTokens["google-ok"] = true
		b.RTMPURL = "rtmp://ingest.test:1935/live"
	})
	h.sessions = config.NewSessionStore(filepath.Join(t.TempDir(), "session.json"))
	if signedIn {
		h.sessions.Save(config.Session{ServerURL: h.backend.URL, RefreshToken: h.backend.IssueRefreshToken()})
	}

	cfg := config.Default()
	cfg.ServerURL = h.backend.URL
	cfg.OBS.WebsocketPassword = "pw"
	h.obs = obstest.New("pw")
	t.Cleanup(h.obs.Close)
	cfg.OBS.WebsocketPort = h.obs.Port()
	for _, o := range opts {
		o(h, &cfg)
	}

	h.c = NewController(cfg, Deps{
		SaveConfig: func(c config.Config) error {
			h.mu.Lock()
			defer h.mu.Unlock()
			h.saved = append(h.saved, c)
			return nil
		},
		Sessions: h.sessions,
		Login: func(_ context.Context, clientID string) (string, error) {
			h.mu.Lock()
			defer h.mu.Unlock()
			h.logins = append(h.logins, clientID)
			return "google-ok", nil
		},
		System:  h.os.system(),
		Hotkeys: h.hotkeys,
		Autostart: func(on bool) error {
			h.mu.Lock()
			defer h.mu.Unlock()
			h.autostart = append(h.autostart, on)
			return nil
		},
		Logf:              t.Logf,
		KeepAlive:         30 * time.Millisecond,
		OBSStartupTimeout: 10 * time.Second,
		JobPollInterval:   5 * time.Millisecond,
	})
	t.Cleanup(h.c.Shutdown)
	return h
}

func (h *harness) waitPhase(p Phase) Status {
	h.t.Helper()
	return h.waitFor(p.String(), func(s Status) bool { return s.Phase == p })
}

func (h *harness) waitFor(what string, cond func(Status) bool) Status {
	h.t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if s := h.c.Status(); cond(s) {
			return s
		}
		time.Sleep(5 * time.Millisecond)
	}
	h.t.Fatalf("timed out waiting for %s; status = %+v", what, h.c.Status())
	return Status{}
}

func freePort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	return ln.Addr().(*net.TCPAddr).Port
}

func hasActivity(s Status, title string) bool {
	for _, a := range s.Activity {
		if a.Title == title {
			return true
		}
	}
	return false
}

func TestResumeGoesLive(t *testing.T) {
	h := newHarness(t, true)
	var phases []Phase
	var mu sync.Mutex
	h.c.Subscribe(func(s Status) {
		mu.Lock()
		defer mu.Unlock()
		if len(phases) == 0 || phases[len(phases)-1] != s.Phase {
			phases = append(phases, s.Phase)
		}
	})
	h.c.Start()
	s := h.waitPhase(PhaseReady)

	if s.User != "streamer" || !s.Live || s.WebsiteURL != apitest.WebsiteURL {
		t.Errorf("status = %+v", s)
	}
	svc := h.obs.StreamService()
	settings, _ := svc["streamServiceSettings"].(map[string]any)
	if settings["server"] != "rtmp://ingest.test:1935/live" || settings["key"] != "key-abc123" {
		t.Errorf("OBS stream settings = %v; want the server-advertised ingest and the user's key", svc)
	}
	if !h.obs.Streaming() {
		t.Error("OBS should be live")
	}
	mu.Lock()
	got := fmt.Sprint(phases)
	mu.Unlock()
	if got != "[loading starting-obs ready]" {
		t.Errorf("phases = %s", got)
	}
	h.waitFor("hotkeys bound", func(s Status) bool { return len(s.Hotkeys) == 3 })
}

func TestSignInFlow(t *testing.T) {
	h := newHarness(t, false)
	h.c.Start()
	h.waitPhase(PhaseSignedOut)

	h.c.SignIn()
	h.waitPhase(PhaseReady)
	h.mu.Lock()
	logins := h.logins
	h.mu.Unlock()
	if len(logins) != 1 || logins[0] != apitest.GoogleClientID {
		t.Errorf("login used client IDs %v; want the one the server provides", logins)
	}
	if sess, _ := h.sessions.Load(); sess.RefreshToken == "" {
		t.Error("session should be saved so the next launch skips sign-in")
	}
}

func TestSignInFailureReturnsToSignedOut(t *testing.T) {
	h := newHarness(t, false)
	h.c.d.Login = func(context.Context, string) (string, error) { return "forged", nil }
	h.c.Start()
	h.waitPhase(PhaseSignedOut)
	h.c.SignIn()
	h.c.Wait()
	s := h.c.Status()
	if s.Phase != PhaseSignedOut || !strings.Contains(s.Message, "rejected") {
		t.Errorf("status = %+v", s)
	}
}

func TestCancelSignIn(t *testing.T) {
	h := newHarness(t, false)
	started := make(chan struct{})
	h.c.d.Login = func(ctx context.Context, _ string) (string, error) {
		close(started)
		<-ctx.Done()
		return "", ctx.Err()
	}
	h.c.Start()
	h.waitPhase(PhaseSignedOut)
	h.c.SignIn()
	<-started
	if p := h.c.Status().Phase; p != PhaseSigningIn {
		t.Fatalf("phase = %s", p)
	}
	h.c.CancelSignIn()
	h.c.Wait()
	if s := h.c.Status(); s.Phase != PhaseSignedOut || s.Message != "" {
		t.Errorf("cancelling is not an error: %+v", s)
	}
}

func TestExpiredSession(t *testing.T) {
	h := newHarness(t, true)
	h.backend.RevokeAll()
	h.c.Start()
	s := h.waitPhase(PhaseSignedOut)
	if !strings.Contains(s.Message, "expired") {
		t.Errorf("message = %q", s.Message)
	}
}

func TestServerUnreachable(t *testing.T) {
	h := newHarness(t, true)
	h.backend.Close()
	h.c.Start()
	s := h.waitPhase(PhaseError)
	if !strings.Contains(s.Message, "internet connection") {
		t.Errorf("message = %q", s.Message)
	}
}

func TestNeedsServerThenSetServer(t *testing.T) {
	h := newHarness(t, false, func(_ *harness, cfg *config.Config) { cfg.ServerURL = "" })
	h.c.Start()
	h.waitPhase(PhaseNeedsServer)

	if err := h.c.SetServer(context.Background(), "http://127.0.0.1:1"); err == nil {
		t.Error("an unreachable server should be rejected")
	}
	if err := h.c.SetServer(context.Background(), h.backend.URL+"/"); err != nil {
		t.Fatal(err)
	}
	h.waitPhase(PhaseSignedOut)
	if got := h.c.Config().ServerURL; got != h.backend.URL {
		t.Errorf("server = %q", got)
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if len(h.saved) == 0 || h.saved[len(h.saved)-1].ServerURL != h.backend.URL {
		t.Error("server choice should be saved")
	}
}

func TestOBSOpenButUncontrollable(t *testing.T) {
	h := newHarness(t, true, withoutOBSRunning)
	h.os.running = true
	h.c.Start()
	s := h.waitPhase(PhaseError)
	if !strings.Contains(s.Message, "Close OBS") {
		t.Errorf("message = %q", s.Message)
	}
}

func TestOBSWrongPassword(t *testing.T) {
	h := newHarness(t, true, func(_ *harness, cfg *config.Config) { cfg.OBS.WebsocketPassword = "stale" })
	h.c.Start()
	s := h.waitPhase(PhaseError)
	if !strings.Contains(s.Message, "Close OBS") {
		t.Errorf("message = %q", s.Message)
	}
}

// launchable leaves no OBS running, so the controller launches the test binary as OBS.
func launchable(h *harness, cfg *config.Config) {
	cfg.OBS.WebsocketPort = freePort(h.t)
	cfg.OBS.WebsocketPassword = "launch-pw"
	quit := filepath.Join(h.t.TempDir(), "quit")
	h.t.Setenv("FAKE_OBS_PORT", strconv.Itoa(cfg.OBS.WebsocketPort))
	h.t.Setenv("FAKE_OBS_QUIT_FILE", quit)
	// The fake OS has no XDG_CONFIG_HOME, so OBS's config is under its home.
	h.t.Setenv("FAKE_OBS_CONFIG", filepath.Join(h.os.home, ".config", "obs-studio", "plugin_config", "obs-websocket", "config.json"))
}

func quitFile(t *testing.T) string { return os.Getenv("FAKE_OBS_QUIT_FILE") }

func TestInstallLaunchAndCloseOBS(t *testing.T) {
	h := newHarness(t, true, launchable)
	port := h.c.Config().OBS.WebsocketPort

	h.c.Start()
	s := h.waitPhase(PhaseNeedsOBS)
	if !strings.Contains(s.Message, "OBS Studio") {
		t.Errorf("message = %q", s.Message)
	}

	h.c.InstallOBS()
	h.waitPhase(PhaseReady)
	h.os.mu.Lock()
	installs := h.os.installs
	h.os.mu.Unlock()
	if len(installs) != 2 || !strings.Contains(installs[1], "flatpak install") {
		t.Errorf("install commands = %v", installs)
	}

	// The launched OBS is controllable and live.
	conn, err := obs.Dial(context.Background(), fmt.Sprintf("ws://127.0.0.1:%d", port), "launch-pw")
	if err != nil {
		t.Fatalf("launched OBS not reachable: %v", err)
	}
	if active, _ := conn.StreamActive(context.Background()); !active {
		t.Error("launched OBS should be streaming")
	}
	conn.Close()

	// Quitting closes the OBS that vod-client launched.
	h.c.Shutdown()
	if _, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 200*time.Millisecond); err == nil {
		t.Error("OBS should have been closed on exit")
	}
	if !h.hotkeys.closed {
		t.Error("hotkeys should be released on exit")
	}
}

func TestUserClosesLaunchedOBS(t *testing.T) {
	h := newHarness(t, true, launchable)
	h.os.installed = true
	h.c.Start()
	h.waitPhase(PhaseReady)

	os.WriteFile(quitFile(t), nil, 0o600)
	s := h.waitPhase(PhaseError)
	if !strings.Contains(s.Message, "OBS was closed") || s.Live {
		t.Errorf("status = %+v", s)
	}

	// Retry relaunches it.
	os.Remove(quitFile(t))
	h.c.Retry()
	h.waitPhase(PhaseReady)
}

func TestAttachedOBSIsLeftRunningOnExit(t *testing.T) {
	h := newHarness(t, true)
	h.c.Start()
	h.waitPhase(PhaseReady)
	h.c.Shutdown()
	if len(h.obs.RequestsOf("StopStream")) != 0 {
		t.Error("must not stop the stream of an OBS the user opened themselves")
	}
}

func TestHotkeyPressActs(t *testing.T) {
	h := newHarness(t, true)
	h.c.Start()
	h.waitFor("hotkeys bound", func(s Status) bool { return len(s.Hotkeys) == 3 })
	s := h.c.Status()
	if s.Hotkeys[ActionClip] != "Alt+F10" || s.Hotkeys[ActionSection] != "Alt+F9" || s.Hotkeys[ActionMarker] != "Alt+F11" {
		t.Errorf("hotkeys = %v", s.Hotkeys)
	}

	h.hotkeys.pressed <- ActionClip
	h.waitFor("clip activity", func(s Status) bool { return hasActivity(s, "Clip saved") })
	if len(h.backend.CallsTo("/api/v1/media/clip")) != 1 {
		t.Error("clip request not sent")
	}

	h.hotkeys.pressed <- ActionSection
	h.waitFor("section in progress", func(s Status) bool { return s.Section })
	h.hotkeys.pressed <- ActionSection
	h.waitFor("section saved", func(s Status) bool { return !s.Section && hasActivity(s, "Section saved") })
}

func TestHotkeysUnavailable(t *testing.T) {
	h := newHarness(t, true)
	h.hotkeys.err = fmt.Errorf("register: %w", hotkey.ErrUnavailable)
	h.c.Start()
	s := h.waitFor("hotkey problem", func(s Status) bool { return s.HotkeyProblem != "" })
	if s.Phase != PhaseReady || !strings.Contains(s.HotkeyProblem, "tray menu") {
		t.Errorf("status = %+v; the app should still be usable without hotkeys", s)
	}
}

func TestHotkeysDeclinedCanBeRetried(t *testing.T) {
	h := newHarness(t, true)
	h.hotkeys.mu.Lock()
	h.hotkeys.err = hotkey.ErrCancelled
	h.hotkeys.mu.Unlock()
	h.c.Start()
	s := h.waitFor("hotkey problem", func(s Status) bool { return s.HotkeyProblem != "" })
	if !strings.Contains(s.HotkeyProblem, "declined") {
		t.Errorf("problem = %q", s.HotkeyProblem)
	}

	h.hotkeys.mu.Lock()
	h.hotkeys.err = nil
	h.hotkeys.mu.Unlock()
	h.c.RebindHotkeys()
	h.waitFor("hotkeys bound", func(s Status) bool { return len(s.Hotkeys) == 3 && s.HotkeyProblem == "" })
}

func TestActionsBeforeReady(t *testing.T) {
	h := newHarness(t, false)
	h.c.Clip()
	if !hasActivity(h.c.Status(), "Not ready yet") {
		t.Error("pressing a hotkey before setup should explain why nothing happened")
	}
	if len(h.backend.Calls()) != 0 {
		t.Error("nothing should reach the server")
	}
}

func TestSetLiveAndKeepAlive(t *testing.T) {
	h := newHarness(t, true)
	h.c.Start()
	h.waitPhase(PhaseReady)

	h.c.SetLive(false)
	h.waitFor("offline", func(s Status) bool { return !s.Live })
	time.Sleep(100 * time.Millisecond) // several keep-alive ticks
	if h.obs.Streaming() {
		t.Error("keep-alive must not restart a stream the user stopped")
	}

	h.c.SetLive(true)
	h.waitFor("live", func(s Status) bool { return s.Live })
	h.obs.SetStreaming(false) // the stream drops
	s := h.waitFor("restart", func(s Status) bool { return hasActivity(s, "Stream restarted") })
	if !h.obs.Streaming() || !s.Live {
		t.Error("dropped stream should be restarted")
	}
}

func TestReconnectsToOBS(t *testing.T) {
	h := newHarness(t, true)
	h.c.Start()
	h.waitPhase(PhaseReady)
	before := len(h.obs.RequestsOf("GetStreamStatus"))
	h.obs.DropConnections()
	h.waitFor("keep-alive over a new connection", func(Status) bool {
		return len(h.obs.RequestsOf("GetStreamStatus")) > before+1
	})
	if p := h.c.Status().Phase; p != PhaseReady {
		t.Errorf("phase = %s", p)
	}
}

func TestUpdateSettings(t *testing.T) {
	h := newHarness(t, true)
	h.c.Start()
	h.waitFor("hotkeys bound", func(s Status) bool { return len(s.Hotkeys) == 3 })
	binds := h.hotkeys.bindCount()

	if err := h.c.UpdateSettings(func(c *config.Config) { c.ClipSeconds = 999 }); err == nil {
		t.Error("invalid settings must be rejected")
	}
	h.mu.Lock()
	if len(h.saved) != 0 {
		t.Error("invalid settings must not be saved")
	}
	h.mu.Unlock()

	if err := h.c.UpdateSettings(func(c *config.Config) {
		c.ClipSeconds = 60
		c.StartOnLogin = false
		c.Hotkeys.Clip = "ctrl+shift+c"
	}); err != nil {
		t.Fatal(err)
	}
	h.mu.Lock()
	if len(h.autostart) != 1 || h.autostart[0] {
		t.Errorf("autostart calls = %v", h.autostart)
	}
	h.mu.Unlock()
	s := h.waitFor("rebind", func(s Status) bool { return s.Hotkeys[ActionClip] == "Ctrl+Shift+C" })
	if h.hotkeys.bindCount() != binds+1 {
		t.Errorf("binds = %d, want one more", h.hotkeys.bindCount())
	}
	_ = s

	h.c.Clip()
	h.waitFor("clip", func(Status) bool { return len(h.backend.CallsTo("/api/v1/media/clip")) == 1 })
	if d := h.backend.CallsTo("/api/v1/media/clip")[0].Body["duration"]; d != 60.0 {
		t.Errorf("clip length = %v, want the new 60s", d)
	}

	// Unrelated changes do not re-prompt for shortcuts.
	h.c.UpdateSettings(func(c *config.Config) { c.ClipSeconds = 90 })
	time.Sleep(50 * time.Millisecond)
	if h.hotkeys.bindCount() != binds+1 {
		t.Error("changing the clip length must not rebind hotkeys")
	}
}

func TestSignOut(t *testing.T) {
	h := newHarness(t, true)
	h.c.Start()
	h.waitPhase(PhaseReady)

	h.c.SignOut()
	h.c.Wait()
	s := h.c.Status()
	if s.Phase != PhaseSignedOut || s.User != "" || s.Live {
		t.Errorf("status = %+v", s)
	}
	if h.obs.Streaming() {
		t.Error("signing out should stop streaming with the old account's key")
	}
	if sess, _ := h.sessions.Load(); sess.RefreshToken != "" {
		t.Error("session should be cleared")
	}
	h.c.Clip()
	if !hasActivity(h.c.Status(), "Not ready yet") {
		t.Error("actions should be unavailable after signing out")
	}
}

func TestActivityIsCapped(t *testing.T) {
	h := newHarness(t, false)
	for i := range maxActivity + 5 {
		h.c.Notify("n", strconv.Itoa(i))
	}
	s := h.c.Status()
	if len(s.Activity) != maxActivity {
		t.Fatalf("activity entries = %d", len(s.Activity))
	}
	if s.Activity[0].Body != strconv.Itoa(maxActivity+4) {
		t.Errorf("newest first: got %q", s.Activity[0].Body)
	}
}

func TestNoStreamKey(t *testing.T) {
	h := newHarness(t, true)
	old := apitest.User["streamKey"]
	apitest.User["streamKey"] = ""
	t.Cleanup(func() { apitest.User["streamKey"] = old })
	h.c.Start()
	s := h.waitPhase(PhaseError)
	if !strings.Contains(s.Message, "stream key") {
		t.Errorf("message = %q", s.Message)
	}
}

var _ = api.ErrNotAuthenticated
