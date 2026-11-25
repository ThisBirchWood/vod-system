package obs

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"time"
)

// LaunchArgs are the flags OBS is started with: tucked into the tray, and without the
// crash-recovery prompt that would otherwise block an unattended start after an unclean exit.
var LaunchArgs = []string{"--minimize-to-tray", "--disable-shutdown-check"}

// WebsocketConfigPath is where obs-websocket (OBS 30+) keeps its settings.
func WebsocketConfigPath(inst Installation) string {
	return filepath.Join(inst.ConfigDir, "plugin_config", "obs-websocket", "config.json")
}

// ConfigureWebsocket enables obs-websocket on port with password, keeping any other settings.
// OBS rewrites this file on exit, so it must only be called while OBS is not running.
func ConfigureWebsocket(inst Installation, port int, password string) error {
	path := WebsocketConfigPath(inst)
	settings := map[string]any{}
	data, err := os.ReadFile(path)
	switch {
	case err == nil:
		if err := json.Unmarshal(data, &settings); err != nil {
			return fmt.Errorf("parse %s: %w", path, err)
		}
	case !errors.Is(err, os.ErrNotExist):
		return err
	}

	settings["server_enabled"] = true
	settings["server_port"] = port
	settings["auth_required"] = true
	settings["server_password"] = password
	// Skips the first-run dialog that shows the generated password.
	settings["first_load"] = false

	out, err := json.MarshalIndent(settings, "", "    ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	return os.WriteFile(path, out, 0o600)
}

// Process is an OBS instance started by this client.
type Process struct {
	cmd    *exec.Cmd
	exited chan struct{}
}

// Launch starts OBS in the background.
func Launch(inst Installation) (*Process, error) {
	args := append(append([]string{}, inst.Args...), LaunchArgs...)
	cmd := exec.Command(inst.Command, args...)
	cmd.Dir = inst.Dir
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("start OBS: %w", err)
	}
	p := &Process{cmd: cmd, exited: make(chan struct{})}
	go func() {
		cmd.Wait()
		close(p.exited)
	}()
	return p, nil
}

// Exited is closed when OBS exits.
func (p *Process) Exited() <-chan struct{} { return p.exited }

// Stop asks OBS to quit, killing it if it has not exited within timeout.
func (p *Process) Stop(timeout time.Duration) {
	select {
	case <-p.exited:
		return
	default:
	}
	// OBS shuts down cleanly on SIGTERM; Windows has no equivalent for a GUI process.
	if runtime.GOOS == "windows" {
		p.cmd.Process.Kill()
	} else {
		p.cmd.Process.Signal(sigterm)
	}
	select {
	case <-p.exited:
	case <-time.After(timeout):
		p.cmd.Process.Kill()
		<-p.exited
	}
}

// Connect dials obs-websocket, retrying while OBS starts up, until ctx is done. A rejected
// password fails immediately since retrying cannot fix it.
func Connect(ctx context.Context, port int, password string) (*Client, error) {
	url := fmt.Sprintf("ws://127.0.0.1:%d", port)
	var lastErr error
	for {
		attempt, cancel := context.WithTimeout(ctx, 5*time.Second)
		c, err := Dial(attempt, url, password)
		cancel()
		if err == nil {
			return c, nil
		}
		if errors.Is(err, ErrAuthFailed) {
			return nil, err
		}
		lastErr = err
		select {
		case <-ctx.Done():
			return nil, fmt.Errorf("connect to obs-websocket on port %d: %w", port, lastErr)
		case <-time.After(500 * time.Millisecond):
		}
	}
}
