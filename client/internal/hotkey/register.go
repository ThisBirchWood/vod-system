//go:build windows || (cgo && (linux || darwin))

package hotkey

import (
	"fmt"

	"golang.design/x/hotkey"
)

// Register grabs b system wide. On Linux this needs an X11 session (or XWayland focus); Wayland
// compositors do not let clients grab keys globally.
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

func libMods(m Modifier) []hotkey.Modifier {
	var mods []hotkey.Modifier
	if m&Ctrl != 0 {
		mods = append(mods, hotkey.ModCtrl)
	}
	if m&Shift != 0 {
		mods = append(mods, hotkey.ModShift)
	}
	if m&Alt != 0 {
		mods = append(mods, modAlt)
	}
	if m&Super != 0 {
		mods = append(mods, modSuper)
	}
	return mods
}

var libKeys = func() map[string]hotkey.Key {
	m := map[string]hotkey.Key{
		"space": hotkey.KeySpace, "return": hotkey.KeyReturn, "escape": hotkey.KeyEscape,
		"delete": hotkey.KeyDelete, "tab": hotkey.KeyTab,
		"left": hotkey.KeyLeft, "right": hotkey.KeyRight, "up": hotkey.KeyUp, "down": hotkey.KeyDown,
	}
	letters := []hotkey.Key{hotkey.KeyA, hotkey.KeyB, hotkey.KeyC, hotkey.KeyD, hotkey.KeyE, hotkey.KeyF,
		hotkey.KeyG, hotkey.KeyH, hotkey.KeyI, hotkey.KeyJ, hotkey.KeyK, hotkey.KeyL, hotkey.KeyM, hotkey.KeyN,
		hotkey.KeyO, hotkey.KeyP, hotkey.KeyQ, hotkey.KeyR, hotkey.KeyS, hotkey.KeyT, hotkey.KeyU, hotkey.KeyV,
		hotkey.KeyW, hotkey.KeyX, hotkey.KeyY, hotkey.KeyZ}
	for i, k := range letters {
		m[string(rune('a'+i))] = k
	}
	digits := []hotkey.Key{hotkey.Key0, hotkey.Key1, hotkey.Key2, hotkey.Key3, hotkey.Key4,
		hotkey.Key5, hotkey.Key6, hotkey.Key7, hotkey.Key8, hotkey.Key9}
	for i, k := range digits {
		m[string(rune('0'+i))] = k
	}
	fkeys := []hotkey.Key{hotkey.KeyF1, hotkey.KeyF2, hotkey.KeyF3, hotkey.KeyF4, hotkey.KeyF5,
		hotkey.KeyF6, hotkey.KeyF7, hotkey.KeyF8, hotkey.KeyF9, hotkey.KeyF10, hotkey.KeyF11, hotkey.KeyF12,
		hotkey.KeyF13, hotkey.KeyF14, hotkey.KeyF15, hotkey.KeyF16, hotkey.KeyF17, hotkey.KeyF18,
		hotkey.KeyF19, hotkey.KeyF20}
	for i, k := range fkeys {
		m[fmt.Sprintf("f%d", i+1)] = k
	}
	return m
}()

// Available reports whether this build can register global hotkeys at all.
func Available() bool { return true }
