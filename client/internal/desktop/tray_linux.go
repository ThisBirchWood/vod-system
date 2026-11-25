package desktop

import "github.com/godbus/dbus/v5"

// TrayAvailable reports whether a system tray will show the app's icon. Linux trays implement
// StatusNotifierItem; vanilla GNOME has none without an extension, and closing the window must
// not leave a user with an invisible, unreachable app.
func TrayAvailable() bool {
	conn, err := dbus.ConnectSessionBus()
	if err != nil {
		return false
	}
	defer conn.Close()
	var has bool
	err = conn.BusObject().Call("org.freedesktop.DBus.NameHasOwner", 0, "org.kde.StatusNotifierWatcher").Store(&has)
	return err == nil && has
}
