package ui

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/driver/desktop"
	"fyne.io/fyne/v2/test"
	"fyne.io/fyne/v2/widget"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/app"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/config"
)

// fakeController records what the UI asks for and publishes whatever status the test sets.
type fakeController struct {
	mu         sync.Mutex
	status     app.Status
	cfg        config.Config
	listeners  []func(app.Status)
	calls      []string
	appManaged bool
	serverErr  error
	updateErr  error
}

func newFake() *fakeController {
	cfg := config.Default()
	cfg.ServerURL = "https://vod.example.com"
	return &fakeController{cfg: cfg, appManaged: true, status: app.Status{Phase: app.PhaseLoading}}
}

func (f *fakeController) record(call string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, call)
}

func (f *fakeController) called(call string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, c := range f.calls {
		if c == call {
			return true
		}
	}
	return false
}

func (f *fakeController) publish(s app.Status) {
	f.mu.Lock()
	f.status = s
	ls := append([]func(app.Status){}, f.listeners...)
	f.mu.Unlock()
	for _, l := range ls {
		l(s)
	}
}

func (f *fakeController) Status() app.Status {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.status
}
func (f *fakeController) Subscribe(fn func(app.Status)) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.listeners = append(f.listeners, fn)
}
func (f *fakeController) Config() config.Config {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.cfg
}
func (f *fakeController) HotkeysAppManaged() bool { return f.appManaged }
func (f *fakeController) SetServer(_ context.Context, url string) error {
	f.record("SetServer " + url)
	return f.serverErr
}
func (f *fakeController) SignIn()        { f.record("SignIn") }
func (f *fakeController) CancelSignIn()  { f.record("CancelSignIn") }
func (f *fakeController) SignOut()       { f.record("SignOut") }
func (f *fakeController) InstallOBS()    { f.record("InstallOBS") }
func (f *fakeController) Retry()         { f.record("Retry") }
func (f *fakeController) Clip()          { f.record("Clip") }
func (f *fakeController) Mark()          { f.record("Mark") }
func (f *fakeController) ToggleSection() { f.record("ToggleSection") }
func (f *fakeController) RebindHotkeys() { f.record("RebindHotkeys") }
func (f *fakeController) SetLive(live bool) {
	if live {
		f.record("SetLive true")
	} else {
		f.record("SetLive false")
	}
}
func (f *fakeController) UpdateSettings(change func(*config.Config)) error {
	f.record("UpdateSettings")
	if f.updateErr != nil {
		return f.updateErr
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	change(&f.cfg)
	return nil
}

func newUI(t *testing.T, f *fakeController, opts Options) *UI {
	t.Helper()
	a := test.NewTempApp(t)
	u := New(a, f, opts)
	u.async = func(fn func()) { fn() } // the test driver runs fyne.Do inline, so stay on one goroutine
	u.win.Resize(fyne.NewSize(windowWidth, 640))
	return u
}

// walk visits every object in the tree, including widgets' rendered children.
func walk(o fyne.CanvasObject, visit func(fyne.CanvasObject)) {
	if o == nil {
		return
	}
	visit(o)
	switch v := o.(type) {
	case *fyne.Container:
		for _, c := range v.Objects {
			walk(c, visit)
		}
	case *container.Scroll:
		walk(v.Content, visit)
	case fyne.Widget:
		if _, ok := o.(*HotkeyEntry); ok {
			return // its button is an implementation detail
		}
		for _, c := range test.WidgetRenderer(v).Objects() {
			walk(c, visit)
		}
	}
}

func visible(o fyne.CanvasObject) bool { return o.Visible() }

func findButton(t *testing.T, root fyne.CanvasObject, text string) *widget.Button {
	t.Helper()
	var found *widget.Button
	walk(root, func(o fyne.CanvasObject) {
		if b, ok := o.(*widget.Button); ok && b.Text == text && found == nil {
			found = b
		}
	})
	if found == nil {
		t.Fatalf("no %q button; texts on screen: %v", text, texts(root))
	}
	return found
}

func texts(root fyne.CanvasObject) []string {
	var out []string
	walk(root, func(o fyne.CanvasObject) {
		if !visible(o) {
			return
		}
		switch v := o.(type) {
		case *widget.Label:
			if v.Text != "" {
				out = append(out, v.Text)
			}
		case *widget.Button:
			out = append(out, "["+v.Text+"]")
		case *widget.Hyperlink:
			out = append(out, "<"+v.Text+">")
		}
	})
	return out
}

func onScreen(root fyne.CanvasObject, substr string) bool {
	for _, s := range texts(root) {
		if strings.Contains(s, substr) {
			return true
		}
	}
	return false
}

func content(u *UI) fyne.CanvasObject { return u.win.Content() }

func TestSignedOut(t *testing.T) {
	f := newFake()
	u := newUI(t, f, Options{})
	f.publish(app.Status{Phase: app.PhaseSignedOut, Message: "Your session has expired. Please sign in again."})

	if !onScreen(content(u), "expired") {
		t.Errorf("message not shown: %v", texts(content(u)))
	}
	test.Tap(findButton(t, content(u), "Sign in with Google"))
	if !f.called("SignIn") {
		t.Error("button should sign in")
	}
}

func TestSignedOutHidesServerForBakedInBuilds(t *testing.T) {
	old := config.DefaultServerURL
	t.Cleanup(func() { config.DefaultServerURL = old })

	config.DefaultServerURL = "https://vod.example.com"
	f := newFake()
	u := newUI(t, f, Options{})
	f.publish(app.Status{Phase: app.PhaseSignedOut})
	if onScreen(content(u), "Change server") {
		t.Error("consumers on the official build should never see server settings")
	}

	config.DefaultServerURL = ""
	u = newUI(t, f, Options{})
	f.publish(app.Status{Phase: app.PhaseSignedOut, Message: "x"})
	if !onScreen(content(u), "Server: vod.example.com") || !onScreen(content(u), "Change server") {
		t.Errorf("self-hosted builds show the server: %v", texts(content(u)))
	}
}

func TestServerEntry(t *testing.T) {
	f := newFake()
	f.serverErr = errors.New("couldn't connect to https://nope")
	u := newUI(t, f, Options{})
	f.publish(app.Status{Phase: app.PhaseNeedsServer})

	var entry *widget.Entry
	walk(content(u), func(o fyne.CanvasObject) {
		if e, ok := o.(*widget.Entry); ok {
			entry = e
		}
	})
	if entry == nil {
		t.Fatal("no address field")
	}
	test.Type(entry, "nope")
	test.Tap(findButton(t, content(u), "Continue"))

	if !f.called("SetServer nope") {
		t.Error("should try the typed address")
	}
	if !onScreen(content(u), "Couldn't connect to https://nope") {
		t.Errorf("error not shown: %v", texts(content(u)))
	}
}

func TestSetupSteps(t *testing.T) {
	tests := []struct {
		status app.Status
		button string
		call   string
		shows  string
	}{
		{app.Status{Phase: app.PhaseSigningIn}, "Cancel", "CancelSignIn", "Continue in your browser"},
		{app.Status{Phase: app.PhaseNeedsOBS, Message: "VoD Client uses OBS Studio"}, "Install OBS Studio", "InstallOBS", "uses OBS Studio"},
		{app.Status{Phase: app.PhaseNeedsOBS}, "I've installed it myself", "Retry", "One more step"},
		{app.Status{Phase: app.PhaseError, Message: "OBS was closed."}, "Retry", "Retry", "OBS was closed."},
		{app.Status{Phase: app.PhaseError, Message: "x"}, "Sign out", "SignOut", "Something went wrong"},
	}
	for _, tt := range tests {
		t.Run(tt.status.Phase.String()+"/"+tt.button, func(t *testing.T) {
			f := newFake()
			u := newUI(t, f, Options{})
			f.publish(tt.status)
			if !onScreen(content(u), tt.shows) {
				t.Errorf("%q not shown: %v", tt.shows, texts(content(u)))
			}
			test.Tap(findButton(t, content(u), tt.button))
			if !f.called(tt.call) {
				t.Errorf("%s should call %s", tt.button, tt.call)
			}
		})
	}
}

func TestProgressPhases(t *testing.T) {
	for _, p := range []app.Phase{app.PhaseLoading, app.PhaseInstallingOBS, app.PhaseStartingOBS} {
		f := newFake()
		u := newUI(t, f, Options{})
		f.publish(app.Status{Phase: p, Message: "please wait " + p.String()})
		found := false
		walk(content(u), func(o fyne.CanvasObject) {
			if _, ok := o.(*widget.Activity); ok {
				found = true
			}
		})
		if !found {
			t.Errorf("%s: no progress indicator", p)
		}
		if p != app.PhaseLoading && !onScreen(content(u), "please wait") {
			t.Errorf("%s: message not shown", p)
		}
	}
}

func readyStatus() app.Status {
	return app.Status{
		Phase: app.PhaseReady, User: "streamer", Live: true, WebsiteURL: "https://vod.example.com",
		Hotkeys: map[string]string{app.ActionClip: "Alt+F10", app.ActionSection: "Alt+F9", app.ActionMarker: "Alt+F11"},
	}
}

func TestReady(t *testing.T) {
	f := newFake()
	u := newUI(t, f, Options{})
	f.publish(readyStatus())
	root := content(u)

	for _, want := range []string{"Signed in as streamer", "Alt+F10", "Alt+F9", "Alt+F11", "<Watch your clips online>",
		"Your clips, sections and markers will show up here."} {
		if !onScreen(root, want) {
			t.Errorf("%q not shown: %v", want, texts(root))
		}
	}
	if u.ready.liveLabel.Text != "LIVE" {
		t.Errorf("badge = %q", u.ready.liveLabel.Text)
	}

	test.Tap(findButton(t, root, "Save clip"))
	test.Tap(findButton(t, root, "Start section"))
	test.Tap(findButton(t, root, "Add marker"))
	test.Tap(findButton(t, root, "Stop streaming"))
	for _, c := range []string{"Clip", "ToggleSection", "Mark", "SetLive false"} {
		if !f.called(c) {
			t.Errorf("%s not called", c)
		}
	}
}

func TestReadyUpdatesInPlace(t *testing.T) {
	f := newFake()
	u := newUI(t, f, Options{})
	f.publish(readyStatus())
	before := content(u)

	s := readyStatus()
	s.Live = false
	s.Section = true
	s.Activity = []app.Activity{
		{Time: time.Date(2026, 1, 1, 20, 31, 0, 0, time.Local), Title: "Clip saved", Body: "Clip 20:31"},
		{Time: time.Date(2026, 1, 1, 20, 30, 0, 0, time.Local), Title: "Clip failed", Body: "You're not live yet."},
	}
	f.publish(s)

	if content(u) != before {
		t.Error("status changes within the ready phase should not rebuild the window")
	}
	root := content(u)
	if u.ready.liveLabel.Text != "OFFLINE" {
		t.Errorf("badge = %q", u.ready.liveLabel.Text)
	}
	test.Tap(findButton(t, root, "Go live"))
	if !f.called("SetLive true") {
		t.Error("Go live should start the stream")
	}
	findButton(t, root, "Save section")
	if !onScreen(root, "Clip saved · Clip 20:31") || onScreen(root, "will show up here") {
		t.Errorf("activity not shown: %v", texts(root))
	}
}

func TestReadyHotkeyProblem(t *testing.T) {
	f := newFake()
	u := newUI(t, f, Options{})
	s := readyStatus()
	s.Hotkeys = nil
	s.HotkeyProblem = "Hotkeys are off because the shortcut request was declined."
	f.publish(s)
	if !onScreen(content(u), "declined") {
		t.Errorf("problem not shown: %v", texts(content(u)))
	}
	test.Tap(findButton(t, content(u), "Set up hotkeys again"))
	if !f.called("RebindHotkeys") {
		t.Error("should offer to retry")
	}

	s.HotkeyProblem = "Hotkeys aren't supported on this desktop."
	f.publish(s)
	if !u.ready.banner.Visible() || u.ready.retryKeys.Visible() {
		t.Error("unsupported hotkeys can't be retried; show the note without the button")
	}
	f.publish(readyStatus())
	if u.ready.banner.Visible() {
		t.Error("banner should hide once hotkeys work")
	}
}

func TestCloseWithoutTrayAsks(t *testing.T) {
	f := newFake()
	u := newUI(t, f, Options{TrayAvailable: false})
	u.onClose()
	if u.win.Canvas().Overlays().Top() == nil {
		t.Fatal("closing without a tray quits the app, so it should ask first")
	}
}

func TestCloseWithTrayHides(t *testing.T) {
	f := newFake()
	u := newUI(t, f, Options{TrayAvailable: true})
	n := fyne.NewNotification(config.AppName,
		"Still running in the background, so your hotkeys keep working. Use the tray icon to open or quit.")
	test.AssertNotificationSent(t, n, u.onClose)
	if u.win.Canvas().Overlays().Top() != nil {
		t.Error("should hide, not ask")
	}
}

func TestTrayMenu(t *testing.T) {
	f := newFake()
	u := newUI(t, f, Options{TrayAvailable: true})
	tray := u.tray

	f.publish(app.Status{Phase: app.PhaseStartingOBS})
	if !tray.clip.Disabled || tray.status.Label != "Setting up…" {
		t.Error("actions should be disabled until ready")
	}

	s := readyStatus()
	s.Section = true
	f.publish(s)
	if tray.clip.Disabled || tray.clip.Label != "Save clip  (Alt+F10)" || tray.section.Label != "Save section" ||
		tray.live.Label != "Stop streaming" || tray.status.Label != "● Live" {
		t.Errorf("tray = %q %q %q %q", tray.status.Label, tray.clip.Label, tray.section.Label, tray.live.Label)
	}
	tray.live.Action()
	if !f.called("SetLive false") {
		t.Error("tray live toggle")
	}

	f.publish(app.Status{Phase: app.PhaseError})
	if tray.status.Label != "Needs attention: open VoD Client" {
		t.Errorf("status = %q", tray.status.Label)
	}
}

func TestSettingsApply(t *testing.T) {
	f := newFake()
	u := newUI(t, f, Options{})
	f.publish(readyStatus())

	form := u.newSettingsForm()
	if form.clipLength.Selected != "30 seconds" {
		t.Errorf("clip length = %q", form.clipLength.Selected)
	}
	form.clipLength.SetSelected("2 minutes")
	form.startOnLogin.SetChecked(false)
	form.closeOBS.SetChecked(false)
	form.clipKey.combo = "ctrl+shift+c"

	cfg := f.Config()
	form.apply(&cfg)
	if cfg.ClipSeconds != 120 || cfg.StartOnLogin || cfg.OBS.CloseOnExit || cfg.Hotkeys.Clip != "ctrl+shift+c" {
		t.Errorf("applied config = %+v", cfg)
	}
	if cfg.Hotkeys.Section != "alt+f9" {
		t.Error("untouched hotkeys must be kept")
	}
}

func TestSettingsDesktopManagedHotkeys(t *testing.T) {
	f := newFake()
	f.appManaged = false
	u := newUI(t, f, Options{})
	f.publish(readyStatus())
	form := u.newSettingsForm()
	if form.clipKey != nil {
		t.Error("no recorders when the desktop owns the shortcuts")
	}
	if !onScreen(form.content, "Your desktop manages") {
		t.Errorf("note missing: %v", texts(form.content))
	}
	test.Tap(findButton(t, form.content, "Ask for shortcuts again"))
	if !f.called("RebindHotkeys") {
		t.Error("should ask again")
	}

	cfg := f.Config()
	form.apply(&cfg)
	if cfg.Hotkeys != f.Config().Hotkeys {
		t.Error("desktop-managed hotkeys must not be overwritten")
	}
}

func TestSettingsDialogSaves(t *testing.T) {
	f := newFake()
	u := newUI(t, f, Options{})
	f.publish(readyStatus())
	u.showSettings()
	top := u.win.Canvas().Overlays().Top()
	if top == nil {
		t.Fatal("settings dialog not shown")
	}
	test.Tap(findButton(t, top, "Save"))
	if !f.called("UpdateSettings") {
		t.Error("Save should apply the settings")
	}
}

func TestHotkeyEntryRecords(t *testing.T) {
	test.NewTempApp(t)
	h := NewHotkeyEntry("alt+f10")
	w := test.NewWindow(h)
	defer w.Close()
	if h.button.Text != "Alt+F10" {
		t.Errorf("label = %q", h.button.Text)
	}

	var changed []string
	h.OnChanged = func(s string) { changed = append(changed, s) }

	test.Tap(h.button)
	if !h.Recording() || w.Canvas().Focused() != h {
		t.Fatal("tapping should start recording and take focus")
	}
	h.KeyDown(&fyne.KeyEvent{Name: desktop.KeyControlLeft})
	h.KeyDown(&fyne.KeyEvent{Name: desktop.KeyShiftLeft})
	if h.button.Text != "Ctrl+Shift+…" {
		t.Errorf("while holding modifiers, label = %q", h.button.Text)
	}
	h.KeyDown(&fyne.KeyEvent{Name: fyne.KeyM})
	if h.Recording() || h.Combo() != "ctrl+shift+m" || h.button.Text != "Ctrl+Shift+M" {
		t.Errorf("combo = %q label = %q recording = %v", h.Combo(), h.button.Text, h.Recording())
	}
	if len(changed) != 1 || changed[0] != "ctrl+shift+m" {
		t.Errorf("OnChanged calls = %v", changed)
	}
}

func TestHotkeyEntryNeedsModifier(t *testing.T) {
	test.NewTempApp(t)
	h := NewHotkeyEntry("alt+f10")
	w := test.NewWindow(h)
	defer w.Close()
	test.Tap(h.button)
	h.KeyDown(&fyne.KeyEvent{Name: fyne.KeyF10})
	if !h.Recording() || h.Combo() != "alt+f10" {
		t.Error("a bare key would be swallowed system wide; keep recording")
	}
	if !strings.Contains(h.button.Text, "modifier") {
		t.Errorf("should explain why: %q", h.button.Text)
	}
	h.KeyDown(&fyne.KeyEvent{Name: desktop.KeyAltRight})
	h.KeyDown(&fyne.KeyEvent{Name: fyne.KeyF8})
	if h.Combo() != "alt+f8" {
		t.Errorf("combo = %q", h.Combo())
	}
}

func TestHotkeyEntryCancelAndClear(t *testing.T) {
	test.NewTempApp(t)
	h := NewHotkeyEntry("alt+f10")
	w := test.NewWindow(h)
	defer w.Close()

	test.Tap(h.button)
	h.KeyDown(&fyne.KeyEvent{Name: fyne.KeyEscape})
	if h.Recording() || h.Combo() != "alt+f10" {
		t.Error("Escape should cancel without changing the hotkey")
	}

	test.Tap(h.button)
	h.KeyDown(&fyne.KeyEvent{Name: fyne.KeyBackspace})
	if h.Combo() != "" || h.button.Text != "Not set" {
		t.Errorf("Backspace should clear: combo %q label %q", h.Combo(), h.button.Text)
	}

	// Keys are ignored unless recording.
	h.KeyDown(&fyne.KeyEvent{Name: desktop.KeyAltLeft})
	h.KeyDown(&fyne.KeyEvent{Name: fyne.KeyF1})
	if h.Combo() != "" {
		t.Error("keys typed while not recording must be ignored")
	}

	// Losing focus stops recording.
	test.Tap(h.button)
	w.Canvas().Unfocus()
	if h.Recording() {
		t.Error("focus loss should stop recording")
	}
}

func TestWrapText(t *testing.T) {
	got := wrapText("VoD Client uses OBS Studio, a free recording app, to capture your screen.", 30)
	want := "VoD Client uses OBS Studio, a\nfree recording app, to capture\nyour screen."
	if got != want {
		t.Errorf("got %q, want %q", got, want)
	}
	for _, line := range strings.Split(got, "\n") {
		if len(line) > 30 {
			t.Errorf("line too long: %q", line)
		}
	}
	if wrapText("a-very-long-unbreakable-word", 5) != "a-very-long-unbreakable-word" {
		t.Error("words longer than the width stay whole")
	}
	if wrapText("one\ntwo", 50) != "one\ntwo" {
		t.Error("explicit line breaks are kept")
	}
}
