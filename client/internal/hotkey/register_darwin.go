//go:build cgo

package hotkey

/*
#cgo LDFLAGS: -framework Carbon
#include "carbon_darwin.h"
*/
import "C"

import (
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
)

// Carbon status codes worth explaining to the user.
const (
	eventHotKeyExistsErr = -9878 // another app already registered the combination
	eventInternalErr     = -9868 // macOS 15 returns this for Option-only combinations
)

var (
	carbonMu   sync.Mutex
	carbonRegs = map[uint32]*carbonRegistration{}
	carbonIDs  atomic.Uint32
)

// Register registers b with Carbon's RegisterEventHotKey, which needs no Accessibility or Input
// Monitoring permission. The app's main run loop must be running (Fyne's is) for presses to
// arrive, and for Register to return.
func Register(b Binding) (Registration, error) {
	key, ok := libKeys[b.Key]
	if !ok {
		return nil, fmt.Errorf("hotkey %s: unsupported key", b)
	}
	if err := Validate(b); err != nil {
		return nil, fmt.Errorf("register %s: %w", b.Display(), err)
	}
	var mods uint32
	for _, m := range libMods(b.Mods) {
		mods |= uint32(m) // the library's macOS modifiers are Carbon's masks
	}

	r := &carbonRegistration{id: carbonIDs.Add(1), pressed: make(chan struct{}, 1)}
	carbonMu.Lock()
	carbonRegs[r.id] = r
	carbonMu.Unlock()

	call := C.vodCall{id: C.UInt32(r.id), key: C.UInt32(key), mods: C.UInt32(mods)}
	if status := C.vodRegister(&call); status != 0 {
		carbonMu.Lock()
		delete(carbonRegs, r.id)
		carbonMu.Unlock()
		return nil, fmt.Errorf("register %s: %w", b.Display(), carbonError(int(status)))
	}
	r.ref = call.ref
	return r, nil
}

func carbonError(status int) error {
	switch status {
	case eventHotKeyExistsErr:
		return errors.New("another app is already using this shortcut")
	case eventInternalErr:
		return ErrOptionOnly
	default:
		return fmt.Errorf("RegisterEventHotKey failed with status %d", status)
	}
}

type carbonRegistration struct {
	id      uint32
	ref     C.EventHotKeyRef
	pressed chan struct{}
	once    sync.Once
}

func (r *carbonRegistration) Pressed() <-chan struct{} { return r.pressed }

func (r *carbonRegistration) Unregister() error {
	var err error
	r.once.Do(func() {
		carbonMu.Lock()
		delete(carbonRegs, r.id)
		carbonMu.Unlock()
		call := C.vodCall{ref: r.ref}
		if status := C.vodUnregister(&call); status != 0 {
			err = fmt.Errorf("UnregisterEventHotKey failed with status %d", int(status))
		}
	})
	return err
}

//export vodHotkeyPressed
func vodHotkeyPressed(id C.UInt32) {
	carbonMu.Lock()
	r := carbonRegs[uint32(id)]
	carbonMu.Unlock()
	if r == nil {
		return
	}
	// Runs on the main thread: never block it. A press already queued covers key repeat.
	select {
	case r.pressed <- struct{}{}:
	default:
	}
}
