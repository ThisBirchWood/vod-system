package app

import (
	"context"
	"errors"
	"fmt"
	"os"
	"time"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/api"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/config"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/control"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/hotkey"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/notify"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/obs"
)

// Runner is the long-running client: it logs in, brings up OBS streaming to the VoD system and
// serves hotkeys and control commands until its context is cancelled.
type Runner struct {
	Config     config.Config
	API        *api.Client
	Login      func(ctx context.Context) (googleIDToken string, err error)
	System     obs.System
	Notifier   notify.Notifier
	SocketPath string
	NoInstall  bool
	Logf       func(format string, args ...any)

	// RegisterHotkey defaults to hotkey.Register.
	RegisterHotkey func(hotkey.Binding) (hotkey.Registration, error)
	// KeepAlive is how often the stream is checked and restarted if it dropped (default 15s).
	KeepAlive time.Duration
}

// Run blocks until ctx is cancelled, OBS exits, or startup fails.
func (r *Runner) Run(ctx context.Context) error {
	user, err := EnsureSession(ctx, r.API, r.Login, r.Logf)
	if err != nil {
		return err
	}
	if user.StreamKey == "" {
		return errors.New("your account has no stream key")
	}
	r.Logf("logged in as %s", user.Username)

	ingest, err := r.Config.IngestURL()
	if err != nil {
		return err
	}

	// Claim the control socket first so a second instance fails before touching OBS.
	ctl, err := control.Listen(r.SocketPath)
	if err != nil {
		return err
	}
	defer ctl.Close()

	client, proc, err := r.startOBS(ctx)
	if err != nil {
		return err
	}
	defer func() {
		client.Close()
		if proc != nil && r.Config.OBS.CloseOnExit {
			r.Logf("closing OBS")
			proc.Stop(10 * time.Second)
		}
	}()

	if err := client.SetCustomRTMP(ctx, ingest, user.StreamKey); err != nil {
		return fmt.Errorf("configure OBS stream output: %w", err)
	}
	r.Logf("OBS streams to %s", ingest)
	if kind, err := client.EnsureCaptureSource(ctx); err != nil {
		r.Logf("warning: could not add a screen capture source: %v", err)
	} else if kind != "" {
		r.Logf("scene was empty; added a %s source", kind)
	}

	if r.Config.OBS.AutoStartStream {
		if err := client.StartStream(ctx); err != nil {
			return fmt.Errorf("start streaming: %w", err)
		}
		r.Logf("streaming started")
	}

	actions := &Actions{Backend: r.API, Notifier: r.Notifier, ClipSeconds: r.Config.ClipSeconds}
	go ctl.Serve(ctx, actions.Handle)

	regs := r.registerHotkeys(ctx, actions)
	defer func() {
		for _, reg := range regs {
			reg.Unregister()
		}
	}()

	r.Notifier.Notify("VoD Client ready", readyMessage(r.Config.Hotkeys, len(regs) > 0))

	err = r.supervise(ctx, &client, proc)

	if proc != nil && r.Config.OBS.CloseOnExit {
		stopCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		if err := client.StopStream(stopCtx); err != nil {
			r.Logf("warning: stop stream: %v", err)
		}
		cancel()
	}
	return err
}

// supervise keeps the stream alive and the OBS connection up until ctx ends or OBS exits.
func (r *Runner) supervise(ctx context.Context, client **obs.Client, proc *obs.Process) error {
	var exited <-chan struct{}
	if proc != nil {
		exited = proc.Exited()
	}
	interval := r.KeepAlive
	if interval == 0 {
		interval = 15 * time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return nil
		case <-exited:
			return errors.New("OBS exited")
		case <-(*client).Done():
			r.Logf("lost connection to OBS; reconnecting")
			reconnectCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
			c, err := obs.Connect(reconnectCtx, r.Config.OBS.WebsocketPort, r.Config.OBS.WebsocketPassword)
			cancel()
			if err != nil {
				if ctx.Err() != nil {
					return nil
				}
				return fmt.Errorf("OBS went away: %w", err)
			}
			*client = c
		case <-ticker.C:
			if !r.Config.OBS.AutoStartStream {
				continue
			}
			started, err := EnsureStreaming(ctx, *client)
			if err != nil {
				r.Logf("warning: keep-alive: %v", err)
			} else if started {
				r.Logf("stream had stopped; restarted it")
				r.Notifier.Notify("Stream restarted", "OBS had stopped streaming")
			}
		}
	}
}

// Streamer is the part of the OBS client the keep-alive uses.
type Streamer interface {
	StreamActive(ctx context.Context) (bool, error)
	StartStream(ctx context.Context) error
}

// EnsureStreaming starts the stream if it is not active, reporting whether it had to.
func EnsureStreaming(ctx context.Context, s Streamer) (bool, error) {
	active, err := s.StreamActive(ctx)
	if err != nil || active {
		return false, err
	}
	return true, s.StartStream(ctx)
}

// startOBS connects to a running OBS, or installs and launches one. The returned process is nil
// when attaching to an OBS that was already running.
func (r *Runner) startOBS(ctx context.Context) (*obs.Client, *obs.Process, error) {
	port, password := r.Config.OBS.WebsocketPort, r.Config.OBS.WebsocketPassword

	quick, cancel := context.WithTimeout(ctx, 2*time.Second)
	client, err := obs.Dial(quick, fmt.Sprintf("ws://127.0.0.1:%d", port), password)
	cancel()
	if err == nil {
		r.Logf("attached to running OBS")
		return client, nil, nil
	}
	if errors.Is(err, obs.ErrAuthFailed) {
		return nil, nil, fmt.Errorf("OBS is running but rejected the websocket password; set obs.websocket_password "+
			"to the one in OBS (Tools → WebSocket Server Settings), or quit OBS and rerun: %w", err)
	}
	if obs.IsRunning(ctx, r.System) {
		return nil, nil, fmt.Errorf("OBS is running without its websocket server on port %d; quit OBS and rerun "+
			"so vod-client can configure it", port)
	}

	inst, err := obs.Find(ctx, r.System, r.Config.OBS.Path)
	if errors.Is(err, obs.ErrNotInstalled) {
		if r.NoInstall || !r.Config.OBS.AutoInstall {
			return nil, nil, errors.New("OBS Studio is not installed (automatic install is disabled)")
		}
		r.Logf("OBS Studio not found; installing it")
		if err := obs.Install(ctx, r.System, func(s string) { r.Logf("%s", s) }); err != nil {
			return nil, nil, fmt.Errorf("install OBS: %w", err)
		}
		inst, err = obs.Find(ctx, r.System, r.Config.OBS.Path)
	}
	if err != nil {
		return nil, nil, err
	}

	if err := obs.ConfigureWebsocket(inst, port, password); err != nil {
		return nil, nil, fmt.Errorf("configure obs-websocket: %w", err)
	}
	proc, err := obs.Launch(inst)
	if err != nil {
		return nil, nil, err
	}
	r.Logf("launched OBS in the background")

	connectCtx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	type result struct {
		c   *obs.Client
		err error
	}
	done := make(chan result, 1)
	go func() {
		c, err := obs.Connect(connectCtx, port, password)
		done <- result{c, err}
	}()
	select {
	case res := <-done:
		if res.err != nil {
			proc.Stop(5 * time.Second)
			return nil, nil, res.err
		}
		return res.c, proc, nil
	case <-proc.Exited():
		cancel()
		return nil, nil, errors.New("OBS exited during startup")
	}
}

func (r *Runner) registerHotkeys(ctx context.Context, actions *Actions) []hotkey.Registration {
	register := r.RegisterHotkey
	if register == nil {
		register = hotkey.Register
	}

	bindings := []struct {
		action, combo string
	}{
		{ActionClip, r.Config.Hotkeys.Clip},
		{ActionMarker, r.Config.Hotkeys.Marker},
		{ActionSection, r.Config.Hotkeys.Section},
	}

	var regs []hotkey.Registration
	for _, b := range bindings {
		if b.combo == "" {
			continue
		}
		binding, err := hotkey.Parse(b.combo)
		if err != nil {
			r.Logf("warning: %v", err)
			continue
		}
		reg, err := register(binding)
		if err != nil {
			r.Logf("warning: %v", err)
			if os.Getenv("WAYLAND_DISPLAY") != "" {
				r.Logf("Wayland does not allow global hotkeys; bind `vod-client %s` in your desktop's keyboard settings instead", b.action)
			}
			continue
		}
		r.Logf("hotkey %s → %s", binding, b.action)
		regs = append(regs, reg)

		action := b.action
		go func() {
			for {
				select {
				case <-ctx.Done():
					return
				case <-reg.Pressed():
					actx, cancel := context.WithTimeout(ctx, 30*time.Second)
					actions.Handle(actx, control.Request{Action: action})
					cancel()
				}
			}
		}()
	}
	return regs
}

func readyMessage(h config.Hotkeys, hotkeys bool) string {
	if !hotkeys {
		return "Streaming. Use `vod-client clip`, `mark` and `section` to capture."
	}
	return fmt.Sprintf("Clip: %s  Section: %s  Marker: %s", h.Clip, h.Section, h.Marker)
}

// EnsureSession resumes the stored session, or runs the browser login if there is none or it has
// expired, and returns the logged-in user.
func EnsureSession(ctx context.Context, c *api.Client, login func(context.Context) (string, error),
	logf func(string, ...any)) (api.User, error) {
	if c.HasSession() {
		u, err := c.Me(ctx)
		if err == nil {
			return u, nil
		}
		if !errors.Is(err, api.ErrNotAuthenticated) {
			return api.User{}, fmt.Errorf("contact backend: %w", err)
		}
		logf("session expired; logging in again")
	}

	token, err := login(ctx)
	if err != nil {
		return api.User{}, err
	}
	if err := c.Login(ctx, token); err != nil {
		return api.User{}, err
	}
	return c.Me(ctx)
}
