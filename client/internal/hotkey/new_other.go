//go:build !linux

package hotkey

// NewManager returns a manager that grabs global hotkeys directly.
func NewManager(appID string) Manager { return NewGrabManager() }
