package hotkey

import (
	"errors"
	"runtime"
)

// ErrOptionOnly is returned for macOS bindings whose only modifiers are Option or Option+Shift,
// which macOS 15 refuses to register (to curb keyloggers); RegisterEventHotKey fails with -9868.
var ErrOptionOnly = errors.New("macOS doesn't allow hotkeys that use only Option or Option+Shift; add Ctrl or Cmd")

// CheckPlatform reports whether b can be registered on goos.
func CheckPlatform(goos string, b Binding) error {
	if goos == "darwin" && b.Mods&Alt != 0 && b.Mods&^(Alt|Shift) == 0 {
		return ErrOptionOnly
	}
	return nil
}

// Validate reports whether b can be registered on this platform.
func Validate(b Binding) error { return CheckPlatform(runtime.GOOS, b) }
