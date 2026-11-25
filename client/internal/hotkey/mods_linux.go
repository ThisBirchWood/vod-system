//go:build cgo

package hotkey

import "golang.design/x/hotkey"

// On X11, Alt is conventionally Mod1 and Super is Mod4.
const (
	modAlt   = hotkey.Mod1
	modSuper = hotkey.Mod4
)
