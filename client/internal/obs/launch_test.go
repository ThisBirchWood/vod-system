package obs

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func TestConfigureWebsocketFreshInstall(t *testing.T) {
	inst := Installation{ConfigDir: t.TempDir()}
	if err := ConfigureWebsocket(inst, 4455, "secret"); err != nil {
		t.Fatal(err)
	}
	got := readJSON(t, WebsocketConfigPath(inst))
	want := map[string]any{
		"server_enabled": true, "server_port": 4455.0, "auth_required": true,
		"server_password": "secret", "first_load": false,
	}
	for k, v := range want {
		if got[k] != v {
			t.Errorf("%s = %v, want %v", k, got[k], v)
		}
	}
	if runtime.GOOS != "windows" {
		info, _ := os.Stat(WebsocketConfigPath(inst))
		if info.Mode().Perm() != 0o600 {
			t.Errorf("permissions %o, want 600", info.Mode().Perm())
		}
	}
}

func TestConfigureWebsocketKeepsOtherSettings(t *testing.T) {
	inst := Installation{ConfigDir: t.TempDir()}
	path := WebsocketConfigPath(inst)
	os.MkdirAll(filepath.Dir(path), 0o700)
	os.WriteFile(path, []byte(`{"alerts_enabled":true,"server_enabled":false,"server_port":4444,"server_password":"old"}`), 0o600)

	if err := ConfigureWebsocket(inst, 4455, "new"); err != nil {
		t.Fatal(err)
	}
	got := readJSON(t, path)
	if got["alerts_enabled"] != true {
		t.Error("unrelated user settings must be preserved")
	}
	if got["server_enabled"] != true || got["server_port"] != 4455.0 || got["server_password"] != "new" {
		t.Errorf("settings not applied: %v", got)
	}
}

func TestConfigureWebsocketRejectsCorruptFile(t *testing.T) {
	inst := Installation{ConfigDir: t.TempDir()}
	path := WebsocketConfigPath(inst)
	os.MkdirAll(filepath.Dir(path), 0o700)
	os.WriteFile(path, []byte(`{broken`), 0o600)
	if err := ConfigureWebsocket(inst, 4455, "pw"); err == nil {
		t.Fatal("overwriting a file we cannot parse would lose user settings; expected an error")
	}
}

func readJSON(t *testing.T, path string) map[string]any {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatal(err)
	}
	return m
}

// TestHelperProcess stands in for OBS when re-executed by the launch tests.
func TestHelperProcess(t *testing.T) {
	out := os.Getenv("FAKE_OBS_ARGS_FILE")
	if out == "" {
		return
	}
	// Record everything after the "--" separator: the arguments OBS would see.
	args := os.Args
	for i, a := range args {
		if a == "--" {
			args = args[i+1:]
			break
		}
	}
	wd, _ := os.Getwd()
	os.WriteFile(out, []byte(wd+"\n"+strings.Join(args, "\n")), 0o600)
	if os.Getenv("FAKE_OBS_EXIT") != "" {
		os.Exit(0)
	}
	time.Sleep(time.Minute)
	os.Exit(0)
}

func helperInstall(t *testing.T, exitImmediately bool) (Installation, string) {
	argsFile := filepath.Join(t.TempDir(), "args")
	t.Setenv("FAKE_OBS_ARGS_FILE", argsFile)
	if exitImmediately {
		t.Setenv("FAKE_OBS_EXIT", "1")
	}
	dir := t.TempDir()
	return Installation{
		Command: os.Args[0],
		Args:    []string{"-test.run=^TestHelperProcess$", "--", "run", "com.example"},
		Dir:     dir,
	}, argsFile
}

func waitForFile(t *testing.T, path string) string {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if data, err := os.ReadFile(path); err == nil && len(data) > 0 {
			return string(data)
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("fake OBS never started")
	return ""
}

func TestLaunchPassesArgsAndDir(t *testing.T) {
	inst, argsFile := helperInstall(t, false)
	p, err := Launch(inst)
	if err != nil {
		t.Fatal(err)
	}
	defer p.Stop(time.Second)

	lines := strings.Split(waitForFile(t, argsFile), "\n")
	wd, _ := filepath.EvalSymlinks(lines[0])
	wantDir, _ := filepath.EvalSymlinks(inst.Dir)
	if wd != wantDir {
		t.Errorf("working dir = %s, want %s", wd, wantDir)
	}
	want := append([]string{"run", "com.example"}, LaunchArgs...)
	if strings.Join(lines[1:], " ") != strings.Join(want, " ") {
		t.Errorf("args = %q, want %q", lines[1:], want)
	}
}

func TestProcessStop(t *testing.T) {
	inst, argsFile := helperInstall(t, false)
	p, err := Launch(inst)
	if err != nil {
		t.Fatal(err)
	}
	waitForFile(t, argsFile)

	done := make(chan struct{})
	go func() {
		p.Stop(2 * time.Second)
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("Stop did not return")
	}
	select {
	case <-p.Exited():
	default:
		t.Fatal("process should have exited")
	}
	p.Stop(time.Second) // stopping twice is harmless
}

func TestProcessExited(t *testing.T) {
	inst, _ := helperInstall(t, true)
	p, err := Launch(inst)
	if err != nil {
		t.Fatal(err)
	}
	select {
	case <-p.Exited():
	case <-time.After(10 * time.Second):
		t.Fatal("Exited not closed after OBS quit")
	}
}

func TestLaunchMissingBinary(t *testing.T) {
	if _, err := Launch(Installation{Command: filepath.Join(t.TempDir(), "nope")}); err == nil {
		t.Fatal("expected an error")
	}
}
