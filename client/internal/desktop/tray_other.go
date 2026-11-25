//go:build !linux

package desktop

// TrayAvailable reports whether a system tray will show the app's icon.
func TrayAvailable() bool { return true }
