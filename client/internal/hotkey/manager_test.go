package hotkey

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"
)

type fakeRegistration struct {
	pressed      chan struct{}
	unregistered bool
}

func (f *fakeRegistration) Pressed() <-chan struct{} { return f.pressed }
func (f *fakeRegistration) Unregister() error {
	f.unregistered = true
	return nil
}

func fakeGrab() (*GrabManager, map[string]*fakeRegistration) {
	regs := map[string]*fakeRegistration{}
	g := NewGrabManager()
	g.Register = func(b Binding) (Registration, error) {
		if b.Key == "f12" {
			return nil, errors.New("already grabbed by another app")
		}
		r := &fakeRegistration{pressed: make(chan struct{}, 1)}
		regs[b.String()] = r
		return r, nil
	}
	return g, regs
}

func TestGrabManagerBindAndPress(t *testing.T) {
	g, regs := fakeGrab()
	defer g.Close()
	bound, err := g.Bind(context.Background(), []Shortcut{
		{ID: "clip", Description: "Save a clip", Binding: Binding{Alt, "f10"}},
		{ID: "mark", Description: "Add a marker", Binding: Binding{Alt, "f11"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if bound["clip"] != "Alt+F10" || bound["mark"] != "Alt+F11" {
		t.Errorf("bound = %v", bound)
	}
	if !g.AppManaged() {
		t.Error("grabbed hotkeys are chosen by the app")
	}

	regs["alt+f11"].pressed <- struct{}{}
	select {
	case id := <-g.Pressed():
		if id != "mark" {
			t.Errorf("pressed %q", id)
		}
	case <-time.After(time.Second):
		t.Fatal("press not delivered")
	}
}

func TestGrabManagerPartialFailure(t *testing.T) {
	g, _ := fakeGrab()
	defer g.Close()
	bound, err := g.Bind(context.Background(), []Shortcut{
		{ID: "clip", Description: "Save a clip", Binding: Binding{Alt, "f10"}},
		{ID: "mark", Description: "Add a marker", Binding: Binding{Alt, "f12"}},
	})
	if err == nil || !strings.Contains(err.Error(), "Add a marker (Alt+F12)") {
		t.Errorf("err = %v, want the failing shortcut named", err)
	}
	if _, ok := bound["clip"]; !ok {
		t.Error("the shortcut that worked should still be bound")
	}
	if _, ok := bound["mark"]; ok {
		t.Error("the failed shortcut must not be reported as bound")
	}
}

func TestGrabManagerRebindReleasesOld(t *testing.T) {
	g, regs := fakeGrab()
	defer g.Close()
	ctx := context.Background()
	g.Bind(ctx, []Shortcut{{ID: "clip", Binding: Binding{Alt, "f10"}}})
	old := regs["alt+f10"]
	g.Bind(ctx, []Shortcut{{ID: "clip", Binding: Binding{Ctrl, "f10"}}})
	if !old.unregistered {
		t.Error("old hotkey must be released when rebinding")
	}

	// A press on the released hotkey goes nowhere.
	old.pressed <- struct{}{}
	select {
	case id := <-g.Pressed():
		t.Errorf("released hotkey delivered %q", id)
	case <-time.After(50 * time.Millisecond):
	}
}
