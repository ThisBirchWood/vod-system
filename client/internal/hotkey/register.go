//go:build windows || (cgo && linux)

package hotkey

import (
	"fmt"

	"golang.design/x/hotkey"
)

// Register grabs b system wide. On Linux this needs an X11 session (or XWayland focus); Wayland
// compositors do not let clients grab keys globally. macOS has its own Register in
// register_darwin.go, because this library's macOS backend needs Accessibility permission.
func Register(b Binding) (Registration, error) {
	key, ok := libKeys[b.Key]
	if !ok {
		return nil, fmt.Errorf("hotkey %s: unsupported key", b)
	}
	hk := hotkey.New(libMods(b.Mods), key)
	if err := hk.Register(); err != nil {
		return nil, fmt.Errorf("register %s: %w", b, err)
	}
	r := &registration{hk: hk, pressed: make(chan struct{}, 1), stop: make(chan struct{})}
	go r.forward()
	return r, nil
}

type registration struct {
	hk      *hotkey.Hotkey
	pressed chan struct{}
	stop    chan struct{}
}

func (r *registration) forward() {
	for {
		select {
		case <-r.hk.Keydown():
			select {
			case r.pressed <- struct{}{}:
			default: // a press is already queued; drop key repeat
			}
		case <-r.stop:
			return
		}
	}
}

func (r *registration) Pressed() <-chan struct{} { return r.pressed }

func (r *registration) Unregister() error {
	close(r.stop)
	return r.hk.Unregister()
}
