//go:build !(windows || (cgo && (linux || darwin)))

package hotkey

import "fmt"

// Register always fails: this build has no global hotkey support (Linux and macOS need cgo).
// The control commands still work and can be bound in the desktop's own shortcut settings.
func Register(b Binding) (Registration, error) {
	return nil, fmt.Errorf("register %s: %w (built without cgo)", b, ErrUnavailable)
}

// Available reports whether this build can register global hotkeys at all.
func Available() bool { return false }
