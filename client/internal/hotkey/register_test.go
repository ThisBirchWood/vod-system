//go:build windows || (cgo && (linux || darwin))

package hotkey

import (
	"os"
	"os/exec"
	"runtime"
	"testing"
	"time"
)

func TestEveryParsableKeyIsRegistrable(t *testing.T) {
	for _, name := range keyNames {
		if _, ok := libKeys[name]; !ok {
			t.Errorf("key %q parses but has no platform key code", name)
		}
	}
	if len(libKeys) != len(keyNames) {
		t.Errorf("libKeys has %d entries, keyNames %d", len(libKeys), len(keyNames))
	}
}

// TestRegisterX11 grabs a real hotkey on an X server (run under xvfb-run in CI). With xdotool
// available it also presses the combination and checks the press is delivered.
func TestRegisterX11(t *testing.T) {
	if runtime.GOOS != "linux" || os.Getenv("DISPLAY") == "" {
		t.Skip("needs an X11 display")
	}
	b, err := Parse("ctrl+alt+f12")
	if err != nil {
		t.Fatal(err)
	}
	reg, err := Register(b)
	if err != nil {
		t.Fatalf("register: %v", err)
	}
	defer func() {
		if err := reg.Unregister(); err != nil {
			t.Errorf("unregister: %v", err)
		}
	}()

	xdotool, err := exec.LookPath("xdotool")
	if err != nil {
		t.Log("xdotool not installed; skipping the key press check")
		return
	}
	// Let the grab settle before synthesising the press.
	time.Sleep(200 * time.Millisecond)
	if out, err := exec.Command(xdotool, "key", "ctrl+alt+F12").CombinedOutput(); err != nil {
		t.Fatalf("xdotool: %v: %s", err, out)
	}
	select {
	case <-reg.Pressed():
	case <-time.After(5 * time.Second):
		t.Fatal("hotkey press was not delivered")
	}
}
