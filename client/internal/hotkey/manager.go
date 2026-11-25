package hotkey

import (
	"context"
	"errors"
	"fmt"
	"sync"
)

// Shortcut is a named action and the key combination that should trigger it.
type Shortcut struct {
	ID          string
	Description string
	Binding     Binding
}

// Manager delivers presses of a set of named shortcuts, whichever way the platform allows.
type Manager interface {
	// Bind replaces the active shortcuts. It returns how each bound shortcut is actually
	// triggered, keyed by ID, which can differ from the request when the desktop lets the user
	// pick. Shortcuts that could not be bound are missing from the map and reported in err.
	Bind(ctx context.Context, shortcuts []Shortcut) (map[string]string, error)
	// Pressed delivers the ID of each shortcut pressed.
	Pressed() <-chan string
	// AppManaged reports whether the app chooses the key combinations itself. When false the
	// desktop owns them and the user changes them in its settings.
	AppManaged() bool
	Close() error
}

// GrabManager grabs keys directly: X11, Windows and macOS.
type GrabManager struct {
	// Register defaults to the platform's Register.
	Register func(Binding) (Registration, error)

	mu      sync.Mutex
	regs    []Registration
	stop    chan struct{}
	pressed chan string
}

// NewGrabManager returns a manager that registers global hotkeys itself.
func NewGrabManager() *GrabManager {
	return &GrabManager{Register: Register, pressed: make(chan string, 8)}
}

func (g *GrabManager) Bind(_ context.Context, shortcuts []Shortcut) (map[string]string, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.unbindLocked()

	g.stop = make(chan struct{})
	bound := map[string]string{}
	var errs []error
	for _, s := range shortcuts {
		reg, err := g.Register(s.Binding)
		if err != nil {
			errs = append(errs, fmt.Errorf("%s (%s): %w", s.Description, s.Binding.Display(), err))
			continue
		}
		g.regs = append(g.regs, reg)
		bound[s.ID] = s.Binding.Display()
		go g.forward(reg, s.ID, g.stop)
	}
	return bound, errors.Join(errs...)
}

func (g *GrabManager) forward(reg Registration, id string, stop <-chan struct{}) {
	for {
		select {
		case <-stop:
			return
		case <-reg.Pressed():
			// select picks randomly among ready cases, so a press can win over a stop that
			// already happened; never deliver presses from a binding that has been replaced.
			select {
			case <-stop:
				return
			default:
			}
			select {
			case g.pressed <- id:
			default: // the app is busy with earlier presses; drop this one
			}
		}
	}
}

func (g *GrabManager) unbindLocked() {
	if g.stop != nil {
		close(g.stop)
		g.stop = nil
	}
	for _, r := range g.regs {
		r.Unregister()
	}
	g.regs = nil
}

func (g *GrabManager) Pressed() <-chan string { return g.pressed }
func (g *GrabManager) AppManaged() bool       { return true }

func (g *GrabManager) Close() error {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.unbindLocked()
	return nil
}
