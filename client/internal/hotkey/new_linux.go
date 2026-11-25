package hotkey

import (
	"os"

	"github.com/godbus/dbus/v5"
)

// NewManager picks how to provide global shortcuts. Wayland only allows them through the
// desktop's GlobalShortcuts portal; elsewhere keys are grabbed from the X server directly.
func NewManager(appID string) Manager {
	if os.Getenv("WAYLAND_DISPLAY") != "" {
		if conn, err := dbus.ConnectSessionBus(); err == nil {
			if m, err := NewPortalManager(conn, appID); err == nil {
				return m
			}
			conn.Close()
		}
	}
	return NewGrabManager()
}
