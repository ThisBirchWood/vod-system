package app

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"slices"
	"sync"
	"time"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/api"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/config"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/hotkey"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/notify"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/obs"
)

// Phase is the step of setup the client is at.
type Phase int

const (
	PhaseLoading       Phase = iota // resuming a saved session
	PhaseNeedsServer                // no server address known
	PhaseSignedOut                  // waiting for the user to sign in
	PhaseSigningIn                  // browser login in progress
	PhaseNeedsOBS                   // OBS is not installed; waiting for the user's go-ahead
	PhaseInstallingOBS              // installing OBS
	PhaseStartingOBS                // launching and configuring OBS
	PhaseReady                      // connected; hotkeys work
	PhaseError                      // something needs the user's attention; Retry starts over
)

var phaseNames = [...]string{"loading", "needs-server", "signed-out", "signing-in", "needs-obs",
	"installing-obs", "starting-obs", "ready", "error"}

func (p Phase) String() string { return phaseNames[p] }

// Activity is an entry in the recent activity list: every notification the user was shown.
type Activity struct {
	Time  time.Time
	Title string
	Body  string
}

const maxActivity = 20

// Status is everything the UI shows. Each change produces a new copy.
type Status struct {
	Phase Phase
	// Message explains the phase: progress, or what went wrong and what to do about it.
	Message string
	User    string
	// WebsiteURL is where the user can watch what they captured; empty until known.
	WebsiteURL string
	Live       bool
	Section    bool
	// Hotkeys maps action IDs to how they are triggered, e.g. "Alt+F10". Actions without a
	// working hotkey are missing.
	Hotkeys map[string]string
	// HotkeyProblem explains why some or all hotkeys are not working.
	HotkeyProblem string
	// Activity is newest first.
	Activity []Activity
}

// Deps are the controller's collaborators.
type Deps struct {
	// SaveConfig persists settings changes.
	SaveConfig func(config.Config) error
	Sessions   api.SessionStore
	// Login runs the browser sign-in for a Google OAuth client and returns an ID token.
	Login    func(ctx context.Context, googleClientID string) (string, error)
	System   obs.System
	Hotkeys  hotkey.Manager // nil disables hotkeys
	Notifier notify.Notifier
	// Autostart turns start-on-login on or off; nil when unsupported.
	Autostart func(enabled bool) error
	Logf      func(format string, args ...any)

	// Timings, overridable in tests.
	KeepAlive         time.Duration // default 15s
	OBSStartupTimeout time.Duration // default 60s
	JobPollInterval   time.Duration // default 2s
}

// Shortcut action IDs and their descriptions, in display order.
var shortcutActions = []struct{ ID, Description string }{
	{ActionClip, "Save a clip"},
	{ActionSection, "Start or save a section"},
	{ActionMarker, "Add a marker"},
}

// Controller runs the client: sign-in, OBS, streaming and the capture actions. The UI calls its
// methods, which return immediately, and renders the Status it publishes.
type Controller struct {
	d Deps

	mu        sync.Mutex
	cfg       config.Config
	status    Status
	listeners []func(Status)
	api       *api.Client
	user      api.User
	actions   *Actions

	flowCancel context.CancelFunc // the running setup flow, if any
	flowDone   chan struct{}

	obs         *obs.Client
	proc        *obs.Process // nil when attached to an OBS the user started
	runCancel   context.CancelFunc
	runDone     chan struct{}
	desiredLive bool
	boundKey    string // what the hotkeys are bound to, to skip needless rebinding
}

// NewController returns a controller with the given settings. Call Start to begin.
func NewController(cfg config.Config, d Deps) *Controller {
	if d.KeepAlive == 0 {
		d.KeepAlive = 15 * time.Second
	}
	if d.OBSStartupTimeout == 0 {
		d.OBSStartupTimeout = 60 * time.Second
	}
	if d.Logf == nil {
		d.Logf = func(string, ...any) {}
	}
	c := &Controller{d: d, cfg: cfg, status: Status{Phase: PhaseLoading}}
	if d.Hotkeys != nil {
		go c.hotkeyLoop()
	}
	return c
}

// Status returns the current status.
func (c *Controller) Status() Status {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.status
}

// Config returns the current settings.
func (c *Controller) Config() config.Config {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.cfg
}

// HotkeysAppManaged reports whether the app picks hotkeys itself (false: the desktop does).
func (c *Controller) HotkeysAppManaged() bool {
	return c.d.Hotkeys == nil || c.d.Hotkeys.AppManaged()
}

// Subscribe registers fn to receive every status change. It is called from background
// goroutines.
func (c *Controller) Subscribe(fn func(Status)) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.listeners = append(c.listeners, fn)
}

func (c *Controller) update(f func(s *Status)) {
	c.mu.Lock()
	f(&c.status)
	s := c.status
	listeners := slices.Clone(c.listeners)
	c.mu.Unlock()
	for _, l := range listeners {
		l(s)
	}
}

func (c *Controller) setPhase(p Phase, msg string) {
	c.d.Logf("%s: %s", p, msg)
	c.update(func(s *Status) { s.Phase, s.Message = p, msg })
}

// Notify shows a notification and records it in the activity list. It implements
// notify.Notifier so the actions report through it.
func (c *Controller) Notify(title, body string) {
	c.d.Logf("notify: %s: %s", title, body)
	c.update(func(s *Status) {
		s.Activity = append([]Activity{{Time: time.Now(), Title: title, Body: body}}, s.Activity...)
		if len(s.Activity) > maxActivity {
			s.Activity = s.Activity[:maxActivity]
		}
	})
	if c.d.Notifier != nil {
		c.d.Notifier.Notify(title, body)
	}
}

// runFlow runs a setup step in the background, cancelling any step already running.
func (c *Controller) runFlow(fn func(ctx context.Context)) {
	c.mu.Lock()
	if c.flowCancel != nil {
		c.flowCancel()
	}
	prev := c.flowDone
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	c.flowCancel, c.flowDone = cancel, done
	c.mu.Unlock()

	go func() {
		defer close(done)
		if prev != nil {
			<-prev
		}
		fn(ctx)
	}()
}

// Wait blocks until the current setup step finishes. Tests use it.
func (c *Controller) Wait() {
	c.mu.Lock()
	done := c.flowDone
	c.mu.Unlock()
	if done != nil {
		<-done
	}
}

// Start resumes a saved session, or asks the user to sign in.
func (c *Controller) Start() { c.runFlow(c.resume) }

// Retry starts over after an error.
func (c *Controller) Retry() { c.runFlow(c.resume) }

func (c *Controller) resume(ctx context.Context) {
	c.teardown(keepRunning)
	c.setPhase(PhaseLoading, "Connecting…")

	cfg := c.Config()
	if cfg.ServerURL == "" {
		c.setPhase(PhaseNeedsServer, "")
		return
	}
	client, err := api.New(cfg.ServerURL, c.d.Sessions)
	if err != nil {
		c.setPhase(PhaseError, "Couldn't read your saved sign-in: "+err.Error())
		return
	}
	c.mu.Lock()
	c.api = client
	c.mu.Unlock()

	if !client.HasSession() {
		c.setPhase(PhaseSignedOut, "")
		return
	}
	user, err := client.Me(ctx)
	switch {
	case errors.Is(err, api.ErrNotAuthenticated):
		c.setPhase(PhaseSignedOut, "Your session has expired. Please sign in again.")
	case err != nil:
		if ctx.Err() == nil {
			c.setPhase(PhaseError, "Couldn't reach the VoD server. Check your internet connection and try again.")
		}
	default:
		c.bringUp(ctx, user)
	}
}

// SetServer checks that url is a VoD server and switches to it. It blocks on the network, so
// call it off the UI thread.
func (c *Controller) SetServer(ctx context.Context, url string) error {
	normalized, err := config.NormalizeServerURL(url)
	if err != nil {
		return err
	}
	probe, err := api.New(normalized, c.d.Sessions)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if _, err := probe.ClientConfig(ctx); err != nil {
		return fmt.Errorf("couldn't connect to %s: %w", normalized, err)
	}
	if err := c.UpdateSettings(func(cfg *config.Config) { cfg.ServerURL = normalized }); err != nil {
		return err
	}
	c.Start()
	return nil
}

// SignIn opens the browser to sign in with Google.
func (c *Controller) SignIn() {
	c.runFlow(func(ctx context.Context) {
		c.mu.Lock()
		client := c.api
		c.mu.Unlock()
		if client == nil {
			c.setPhase(PhaseNeedsServer, "")
			return
		}
		c.setPhase(PhaseSigningIn, "Finish signing in in your browser.")

		fail := func(msg string) {
			if ctx.Err() != nil {
				c.setPhase(PhaseSignedOut, "")
			} else {
				c.setPhase(PhaseSignedOut, msg)
			}
		}
		bootstrap, err := client.ClientConfig(ctx)
		if err != nil {
			fail("Couldn't reach the VoD server. Check your internet connection and try again.")
			return
		}
		token, err := c.d.Login(ctx, bootstrap.GoogleClientID)
		if err != nil {
			fail("Sign-in didn't finish: " + err.Error())
			return
		}
		if err := client.Login(ctx, token); err != nil {
			fail("The VoD server rejected the sign-in: " + err.Error())
			return
		}
		user, err := client.Me(ctx)
		if err != nil {
			fail("Signed in, but couldn't load your account: " + err.Error())
			return
		}
		c.bringUp(ctx, user)
	})
}

// CancelSignIn abandons a sign-in in progress.
func (c *Controller) CancelSignIn() {
	c.mu.Lock()
	cancel := c.flowCancel
	c.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

// SignOut ends the session and stops streaming.
func (c *Controller) SignOut() {
	c.runFlow(func(ctx context.Context) {
		c.teardown(signingOut)
		c.mu.Lock()
		client := c.api
		c.mu.Unlock()
		if client != nil {
			if err := client.Logout(ctx); err != nil {
				c.d.Logf("logout: %v", err)
			}
		}
		c.update(func(s *Status) { s.User, s.Live, s.Section = "", false, false })
		c.setPhase(PhaseSignedOut, "")
	})
}

// InstallOBS installs OBS Studio, then continues setup.
func (c *Controller) InstallOBS() {
	c.runFlow(func(ctx context.Context) {
		c.setPhase(PhaseInstallingOBS, "Installing OBS Studio. This can take a few minutes…")
		err := obs.Install(ctx, c.d.System, func(s string) { c.d.Logf("install: %s", s) })
		if err != nil {
			if ctx.Err() == nil {
				c.setPhase(PhaseError, "Couldn't install OBS Studio automatically ("+err.Error()+
					"). Install it from obsproject.com, then click Retry.")
			}
			return
		}
		c.mu.Lock()
		user := c.user
		c.mu.Unlock()
		c.bringUp(ctx, user)
	})
}

// errNeedsOBS means OBS is not installed.
var errNeedsOBS = errors.New("OBS is not installed")

// bringUp gets OBS streaming to the VoD system and makes the capture actions available.
func (c *Controller) bringUp(ctx context.Context, user api.User) {
	c.mu.Lock()
	c.user = user
	client := c.api
	cfg := c.cfg
	c.mu.Unlock()
	c.update(func(s *Status) { s.User = user.Username })

	if user.StreamKey == "" {
		c.setPhase(PhaseError, "Your account doesn't have a stream key yet. Contact your server's administrator.")
		return
	}
	c.setPhase(PhaseStartingOBS, "Starting OBS…")

	advertised := ""
	if bootstrap, err := client.ClientConfig(ctx); err == nil {
		advertised = bootstrap.RTMPURL
		c.update(func(s *Status) { s.WebsiteURL = bootstrap.WebsiteURL })
	}
	ingest, err := config.IngestURL(cfg.ServerURL, advertised)
	if err != nil {
		c.setPhase(PhaseError, err.Error())
		return
	}

	conn, proc, err := c.connectOBS(ctx, cfg)
	if errors.Is(err, errNeedsOBS) {
		c.setPhase(PhaseNeedsOBS, "VoD Client uses OBS Studio, a free recording app, to capture your screen.")
		return
	}
	if err != nil {
		if ctx.Err() == nil {
			c.setPhase(PhaseError, err.Error())
		}
		return
	}

	fail := func(msg string, err error) {
		conn.Close()
		if proc != nil {
			proc.Stop(5 * time.Second)
		}
		if ctx.Err() == nil {
			c.setPhase(PhaseError, fmt.Sprintf("%s: %v", msg, err))
		}
	}
	if err := conn.SetCustomRTMP(ctx, ingest, user.StreamKey); err != nil {
		fail("Couldn't set up OBS", err)
		return
	}
	if kind, err := conn.EnsureCaptureSource(ctx); err != nil {
		c.d.Logf("could not add a capture source: %v", err)
	} else if kind != "" {
		c.d.Logf("added a %s source to the empty scene", kind)
	}
	live := false
	if cfg.OBS.AutoStartStream {
		if err := conn.StartStream(ctx); err != nil {
			fail("Couldn't go live", err)
			return
		}
		live = true
	}

	actions := &Actions{Backend: client, Notifier: c, ClipSeconds: cfg.ClipSeconds, PollInterval: c.d.JobPollInterval}
	runCtx, runCancel := context.WithCancel(context.Background())
	runDone := make(chan struct{})
	c.mu.Lock()
	c.obs, c.proc, c.actions = conn, proc, actions
	c.desiredLive = live
	c.runCancel, c.runDone = runCancel, runDone
	c.mu.Unlock()
	go c.supervise(runCtx, conn, proc, runDone)

	c.update(func(s *Status) { s.Live, s.Section = live, false })
	c.setPhase(PhaseReady, "")
	go c.bindHotkeys(runCtx, cfg.Hotkeys)
}

// connectOBS attaches to a running OBS, or launches the installed one.
func (c *Controller) connectOBS(ctx context.Context, cfg config.Config) (*obs.Client, *obs.Process, error) {
	port, password := cfg.OBS.WebsocketPort, cfg.OBS.WebsocketPassword

	quick, cancel := context.WithTimeout(ctx, 2*time.Second)
	conn, err := obs.Dial(quick, fmt.Sprintf("ws://127.0.0.1:%d", port), password)
	cancel()
	if err == nil {
		c.d.Logf("attached to running OBS")
		return conn, nil, nil
	}
	if errors.Is(err, obs.ErrAuthFailed) || obs.IsRunning(ctx, c.d.System) {
		return nil, nil, errors.New("OBS is already open, but VoD Client can't control it. Close OBS, then click Retry.")
	}

	inst, err := obs.Find(ctx, c.d.System, cfg.OBS.Path)
	if errors.Is(err, obs.ErrNotInstalled) {
		return nil, nil, errNeedsOBS
	}
	if err != nil {
		return nil, nil, err
	}
	if err := obs.ConfigureWebsocket(inst, port, password); err != nil {
		return nil, nil, fmt.Errorf("couldn't configure OBS: %w", err)
	}
	proc, err := obs.Launch(inst)
	if err != nil {
		return nil, nil, fmt.Errorf("couldn't start OBS: %w", err)
	}
	c.d.Logf("launched OBS")

	connectCtx, cancel := context.WithTimeout(ctx, c.d.OBSStartupTimeout)
	defer cancel()
	type result struct {
		c   *obs.Client
		err error
	}
	done := make(chan result, 1)
	go func() {
		conn, err := obs.Connect(connectCtx, port, password)
		done <- result{conn, err}
	}()
	select {
	case res := <-done:
		if res.err != nil {
			proc.Stop(5 * time.Second)
			return nil, nil, fmt.Errorf("OBS started but didn't respond. Close it and click Retry. (%v)", res.err)
		}
		return res.c, proc, nil
	case <-proc.Exited():
		cancel()
		return nil, nil, errors.New("OBS closed while starting. Click Retry to try again.")
	}
}

// supervise keeps the stream alive and notices OBS going away, until ctx is cancelled. It owns
// conn: it is the only goroutine that replaces it after a reconnect.
func (c *Controller) supervise(ctx context.Context, conn *obs.Client, proc *obs.Process, done chan struct{}) {
	defer close(done)
	var exited <-chan struct{}
	if proc != nil {
		exited = proc.Exited()
	}
	const closedMsg = "OBS was closed. Click Retry to start it again."

	ticker := time.NewTicker(c.d.KeepAlive)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-exited:
			go c.lost(closedMsg)
			return
		case <-conn.Done():
			// The connection also drops when OBS quits, so give up reconnecting the moment the
			// process exits rather than retrying against nothing.
			cfg := c.Config()
			reconnectCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
			go func() {
				select {
				case <-exited:
					cancel()
				case <-reconnectCtx.Done():
				}
			}()
			fresh, err := obs.Connect(reconnectCtx, cfg.OBS.WebsocketPort, cfg.OBS.WebsocketPassword)
			cancel()
			if ctx.Err() != nil {
				if fresh != nil {
					fresh.Close()
				}
				return
			}
			if err != nil {
				select {
				case <-exited:
					go c.lost(closedMsg)
				case <-time.After(time.Second):
					go c.lost("Lost the connection to OBS. Click Retry to reconnect.")
				}
				return
			}
			c.mu.Lock()
			if c.obs == conn {
				c.obs = fresh
			}
			c.mu.Unlock()
			conn = fresh
		case <-ticker.C:
			c.checkStream(ctx, conn)
		}
	}
}

func (c *Controller) checkStream(ctx context.Context, conn *obs.Client) {
	active, err := conn.StreamActive(ctx)
	if err != nil {
		return
	}
	c.mu.Lock()
	want := c.desiredLive
	c.mu.Unlock()
	if want && !active {
		if err := conn.StartStream(ctx); err == nil {
			active = true
			c.Notify("Stream restarted", "OBS had stopped streaming, so VoD Client restarted it.")
		}
	}
	c.update(func(s *Status) { s.Live = active })
}

// lost handles OBS disappearing while the client was ready.
func (c *Controller) lost(msg string) {
	c.teardown(keepRunning)
	c.update(func(s *Status) { s.Live = false })
	c.setPhase(PhaseError, msg)
}

// teardownMode says what happens to OBS when the client disconnects from it.
type teardownMode int

const (
	// keepRunning just disconnects, e.g. before retrying.
	keepRunning teardownMode = iota
	// quitting stops the stream and closes OBS only if the client launched it and the settings
	// allow; an OBS the user opened themselves is theirs to manage.
	quitting
	// signingOut always stops the stream, since it uses the signed-out account's stream key.
	signingOut
)

// teardown stops supervising OBS and disconnects from it.
func (c *Controller) teardown(mode teardownMode) {
	// Stop the supervisor first: it uses the connection until it returns.
	c.mu.Lock()
	runCancel, runDone := c.runCancel, c.runDone
	c.runCancel, c.runDone, c.actions = nil, nil, nil
	c.mu.Unlock()
	if runCancel != nil {
		runCancel()
		<-runDone
	}

	c.mu.Lock()
	conn, proc := c.obs, c.proc
	c.obs, c.proc = nil, nil
	closeOBS := proc != nil && c.cfg.OBS.CloseOnExit && mode != keepRunning
	c.mu.Unlock()
	if conn == nil {
		return
	}

	if mode == signingOut || closeOBS {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		if err := conn.StopStream(ctx); err != nil {
			c.d.Logf("stop stream: %v", err)
		}
		cancel()
	}
	conn.Close()
	if closeOBS {
		proc.Stop(10 * time.Second)
	}
}

// Shutdown is called when the app quits.
func (c *Controller) Shutdown() {
	c.CancelSignIn()
	c.Wait()
	c.teardown(quitting)
	if c.d.Hotkeys != nil {
		c.d.Hotkeys.Close()
	}
}

// SetLive starts or stops streaming.
func (c *Controller) SetLive(live bool) {
	c.mu.Lock()
	conn := c.obs
	c.desiredLive = live
	c.mu.Unlock()
	if conn == nil {
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		var err error
		if live {
			err = conn.StartStream(ctx)
		} else {
			err = conn.StopStream(ctx)
		}
		if err != nil {
			c.Notify("Couldn't change the stream", err.Error())
			return
		}
		c.update(func(s *Status) { s.Live = live })
	}()
}

// Clip saves the last few seconds of the stream.
func (c *Controller) Clip() { c.act(ActionClip, "") }

// Mark adds a marker to the stream.
func (c *Controller) Mark() { c.act(ActionMarker, "") }

// ToggleSection starts a section, or saves the one in progress.
func (c *Controller) ToggleSection() { c.act(ActionSection, "") }

func (c *Controller) act(action, arg string) {
	c.mu.Lock()
	actions := c.actions
	c.mu.Unlock()
	if actions == nil {
		c.Notify("Not ready yet", "Finish setting up VoD Client first.")
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		actions.Handle(ctx, actionRequest(action, arg))
		c.update(func(s *Status) { s.Section = actions.InSection() })
	}()
}

func (c *Controller) hotkeyLoop() {
	for id := range c.d.Hotkeys.Pressed() {
		c.act(id, "")
	}
}

// bindHotkeys binds the configured shortcuts. On Wayland this can wait on a desktop dialog.
func (c *Controller) bindHotkeys(ctx context.Context, keys config.Hotkeys) {
	if c.d.Hotkeys == nil {
		c.update(func(s *Status) {
			s.Hotkeys, s.HotkeyProblem = nil, "Hotkeys aren't available. Use the buttons or the tray menu instead."
		})
		return
	}

	combos := map[string]string{ActionClip: keys.Clip, ActionSection: keys.Section, ActionMarker: keys.Marker}
	var shortcuts []hotkey.Shortcut
	var problems []string
	for _, a := range shortcutActions {
		if combos[a.ID] == "" {
			continue
		}
		b, err := hotkey.Parse(combos[a.ID])
		if err != nil {
			problems = append(problems, err.Error())
			continue
		}
		shortcuts = append(shortcuts, hotkey.Shortcut{ID: a.ID, Description: a.Description, Binding: b})
	}

	key := fmt.Sprint(shortcuts)
	c.mu.Lock()
	unchanged := c.boundKey == key && c.boundKey != ""
	c.mu.Unlock()
	if unchanged {
		return
	}

	bindCtx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()
	bound, err := c.d.Hotkeys.Bind(bindCtx, shortcuts)
	if ctx.Err() != nil {
		return
	}
	switch {
	case errors.Is(err, hotkey.ErrUnavailable):
		problems = append(problems, "Hotkeys aren't supported on this desktop. Use the buttons or the tray menu instead.")
	case errors.Is(err, hotkey.ErrCancelled):
		problems = append(problems, "Hotkeys are off because the shortcut request was declined. You can turn them on again in Settings.")
	case err != nil:
		problems = append(problems, "Some hotkeys couldn't be set up, probably because another app uses them: "+err.Error())
	default:
		c.mu.Lock()
		c.boundKey = key
		c.mu.Unlock()
	}
	problem := ""
	if len(problems) > 0 {
		problem = problems[0]
	}
	c.update(func(s *Status) { s.Hotkeys, s.HotkeyProblem = bound, problem })
}

// RebindHotkeys asks for the shortcuts again, e.g. after the user declined the desktop's
// dialog.
func (c *Controller) RebindHotkeys() {
	c.mu.Lock()
	c.boundKey = ""
	ready := c.actions != nil
	keys := c.cfg.Hotkeys
	c.mu.Unlock()
	if ready {
		go c.bindHotkeys(context.Background(), keys)
	}
}

// UpdateSettings applies and saves a settings change.
func (c *Controller) UpdateSettings(change func(*config.Config)) error {
	c.mu.Lock()
	old := c.cfg
	next := old
	change(&next)
	if err := next.Validate(); err != nil {
		c.mu.Unlock()
		return err
	}
	if c.d.SaveConfig != nil {
		if err := c.d.SaveConfig(next); err != nil {
			c.mu.Unlock()
			return fmt.Errorf("couldn't save settings: %w", err)
		}
	}
	c.cfg = next
	actions := c.actions
	c.mu.Unlock()

	if next.StartOnLogin != old.StartOnLogin && c.d.Autostart != nil {
		if err := c.d.Autostart(next.StartOnLogin); err != nil {
			c.d.Logf("autostart: %v", err)
			return fmt.Errorf("couldn't change the start-on-login setting: %w", err)
		}
	}
	if actions != nil {
		actions.SetClipSeconds(next.ClipSeconds)
		if !reflect.DeepEqual(next.Hotkeys, old.Hotkeys) {
			c.RebindHotkeys()
		}
	}
	return nil
}
