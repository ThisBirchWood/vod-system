package hotkey

import (
	"bufio"
	"context"
	"errors"
	"os/exec"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/godbus/dbus/v5"
	"github.com/godbus/dbus/v5/prop"
)

// privateBus starts a throwaway dbus-daemon and returns its address.
func privateBus(t *testing.T) string {
	t.Helper()
	if _, err := exec.LookPath("dbus-daemon"); err != nil {
		t.Skip("dbus-daemon not installed")
	}
	cmd := exec.Command("dbus-daemon", "--session", "--nofork", "--print-address=1")
	out, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Start(); err != nil {
		t.Skipf("cannot start dbus-daemon: %v", err)
	}
	t.Cleanup(func() {
		cmd.Process.Kill()
		cmd.Wait()
	})
	addr, err := bufio.NewReader(out).ReadString('\n')
	if err != nil {
		t.Fatalf("read bus address: %v", err)
	}
	return strings.TrimSpace(addr)
}

func connect(t *testing.T, addr string) *dbus.Conn {
	t.Helper()
	conn, err := dbus.Connect(addr)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	return conn
}

// fakePortal implements the GlobalShortcuts portal the way xdg-desktop-portal does, with each
// method answering through a Response signal on a Request object.
type fakePortal struct {
	conn *dbus.Conn

	mu         sync.Mutex
	registered string
	sessions   []dbus.ObjectPath
	closed     []dbus.ObjectPath
	bound      []portalShortcut
	// userTrigger, when set, is what the "user" picks instead of the preferred trigger.
	userTrigger string
	// cancel makes BindShortcuts answer as if the user dismissed the dialog.
	cancel bool
}

func newFakePortal(t *testing.T, addr string, withShortcuts bool) *fakePortal {
	t.Helper()
	conn := connect(t, addr)
	f := &fakePortal{conn: conn}

	if withShortcuts {
		if err := conn.ExportMethodTable(map[string]any{
			"CreateSession": f.createSession,
			"BindShortcuts": f.bindShortcuts,
		}, portalPath, shortcutsIntf); err != nil {
			t.Fatal(err)
		}
		if _, err := prop.Export(conn, portalPath, prop.Map{
			shortcutsIntf: {"version": {Value: uint32(1), Emit: prop.EmitFalse}},
		}); err != nil {
			t.Fatal(err)
		}
	}
	conn.ExportMethodTable(map[string]any{"Register": f.register}, portalPath, registryIntf)

	reply, err := conn.RequestName(portalBus, dbus.NameFlagDoNotQueue)
	if err != nil || reply != dbus.RequestNameReplyPrimaryOwner {
		t.Fatalf("own portal name: %v %v", reply, err)
	}
	return f
}

func requestPath(sender dbus.Sender, token string) dbus.ObjectPath {
	s := strings.ReplaceAll(strings.TrimPrefix(string(sender), ":"), ".", "_")
	return dbus.ObjectPath(string(portalPath) + "/request/" + s + "/" + token)
}

// respond emits the Response signal shortly after the method returns, as real portals do.
func (f *fakePortal) respond(path dbus.ObjectPath, code uint32, results map[string]dbus.Variant) {
	go func() {
		time.Sleep(10 * time.Millisecond)
		f.conn.Emit(path, requestIntf+".Response", code, results)
	}()
}

func (f *fakePortal) register(appID string, _ map[string]dbus.Variant) *dbus.Error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.registered = appID
	return nil
}

func (f *fakePortal) createSession(sender dbus.Sender, options map[string]dbus.Variant) (dbus.ObjectPath, *dbus.Error) {
	token, _ := options["handle_token"].Value().(string)
	sessionToken, _ := options["session_handle_token"].Value().(string)
	s := strings.ReplaceAll(strings.TrimPrefix(string(sender), ":"), ".", "_")
	session := dbus.ObjectPath(string(portalPath) + "/session/" + s + "/" + sessionToken)

	f.conn.ExportMethodTable(map[string]any{"Close": func() *dbus.Error {
		f.mu.Lock()
		f.closed = append(f.closed, session)
		f.mu.Unlock()
		return nil
	}}, session, sessionIntf)

	f.mu.Lock()
	f.sessions = append(f.sessions, session)
	f.mu.Unlock()

	path := requestPath(sender, token)
	f.respond(path, 0, map[string]dbus.Variant{"session_handle": dbus.MakeVariant(string(session))})
	return path, nil
}

func (f *fakePortal) bindShortcuts(sender dbus.Sender, session dbus.ObjectPath, shortcuts []portalShortcut,
	_ string, options map[string]dbus.Variant) (dbus.ObjectPath, *dbus.Error) {
	token, _ := options["handle_token"].Value().(string)
	path := requestPath(sender, token)

	f.mu.Lock()
	f.bound = shortcuts
	cancel, userTrigger := f.cancel, f.userTrigger
	f.mu.Unlock()

	if cancel {
		f.respond(path, 1, map[string]dbus.Variant{})
		return path, nil
	}
	var out []portalShortcut
	for _, s := range shortcuts {
		trigger, _ := s.Props["preferred_trigger"].Value().(string)
		if userTrigger != "" {
			trigger = userTrigger
		}
		out = append(out, portalShortcut{ID: s.ID, Props: map[string]dbus.Variant{
			"description":         s.Props["description"],
			"trigger_description": dbus.MakeVariant("Press " + trigger),
		}})
	}
	f.respond(path, 0, map[string]dbus.Variant{"shortcuts": dbus.MakeVariant(out)})
	return path, nil
}

func (f *fakePortal) activate(session dbus.ObjectPath, id string) {
	f.conn.Emit(portalPath, shortcutsIntf+".Activated", session, id, uint64(0), map[string]dbus.Variant{})
}

func (f *fakePortal) lastSession() dbus.ObjectPath {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.sessions[len(f.sessions)-1]
}

var testShortcuts = []Shortcut{
	{ID: "clip", Description: "Save a clip", Binding: Binding{Alt, "f10"}},
	{ID: "mark", Description: "Add a marker", Binding: Binding{Ctrl | Shift, "m"}},
}

func newPortalManager(t *testing.T, addr string) *PortalManager {
	t.Helper()
	m, err := NewPortalManager(connect(t, addr), "dev.example.Test")
	if err != nil {
		t.Fatal(err)
	}
	return m
}

func TestPortalBindAndActivate(t *testing.T) {
	addr := privateBus(t)
	portal := newFakePortal(t, addr, true)
	m := newPortalManager(t, addr)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	bound, err := m.Bind(ctx, testShortcuts)
	if err != nil {
		t.Fatal(err)
	}
	if bound["clip"] != "Press ALT+F10" || bound["mark"] != "Press CTRL+SHIFT+m" {
		t.Errorf("bound = %v", bound)
	}
	if m.AppManaged() {
		t.Error("the desktop owns portal shortcuts")
	}

	portal.mu.Lock()
	if portal.registered != "dev.example.Test" {
		t.Errorf("app id registered as %q", portal.registered)
	}
	if len(portal.bound) != 2 || portal.bound[0].Props["description"].Value() != "Save a clip" {
		t.Errorf("portal got %+v", portal.bound)
	}
	portal.mu.Unlock()

	portal.activate(portal.lastSession(), "mark")
	select {
	case id := <-m.Pressed():
		if id != "mark" {
			t.Errorf("pressed %q", id)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("activation not delivered")
	}
}

func TestPortalUserPicksDifferentTrigger(t *testing.T) {
	addr := privateBus(t)
	portal := newFakePortal(t, addr, true)
	portal.mu.Lock()
	portal.userTrigger = "LOGO+c"
	portal.mu.Unlock()
	m := newPortalManager(t, addr)

	bound, err := m.Bind(context.Background(), testShortcuts[:1])
	if err != nil {
		t.Fatal(err)
	}
	if bound["clip"] != "Press LOGO+c" {
		t.Errorf("the UI must show what the desktop bound, got %v", bound)
	}
}

func TestPortalRebindReplacesSession(t *testing.T) {
	addr := privateBus(t)
	portal := newFakePortal(t, addr, true)
	m := newPortalManager(t, addr)
	ctx := context.Background()

	if _, err := m.Bind(ctx, testShortcuts); err != nil {
		t.Fatal(err)
	}
	first := portal.lastSession()
	if _, err := m.Bind(ctx, testShortcuts[:1]); err != nil {
		t.Fatal(err)
	}
	second := portal.lastSession()
	if first == second {
		t.Fatal("rebinding should use a new session")
	}

	deadline := time.Now().Add(5 * time.Second)
	for {
		portal.mu.Lock()
		closed := len(portal.closed) == 1 && portal.closed[0] == first
		portal.mu.Unlock()
		if closed {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("old session was not closed")
		}
		time.Sleep(10 * time.Millisecond)
	}

	// Presses from the old session are ignored.
	portal.activate(first, "clip")
	portal.activate(second, "clip")
	select {
	case id := <-m.Pressed():
		if id != "clip" {
			t.Errorf("pressed %q", id)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("activation not delivered")
	}
	select {
	case id := <-m.Pressed():
		t.Errorf("stale session press delivered: %q", id)
	case <-time.After(100 * time.Millisecond):
	}
}

func TestPortalCancelled(t *testing.T) {
	addr := privateBus(t)
	portal := newFakePortal(t, addr, true)
	portal.mu.Lock()
	portal.cancel = true
	portal.mu.Unlock()
	m := newPortalManager(t, addr)

	if _, err := m.Bind(context.Background(), testShortcuts); !errors.Is(err, ErrCancelled) {
		t.Fatalf("got %v, want ErrCancelled", err)
	}
}

func TestPortalUnavailable(t *testing.T) {
	addr := privateBus(t)
	newFakePortal(t, addr, false) // a portal without GlobalShortcuts, like older GNOME
	if _, err := NewPortalManager(connect(t, addr), "dev.example.Test"); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("got %v, want ErrUnavailable", err)
	}
}

func TestPortalBindTimesOut(t *testing.T) {
	addr := privateBus(t)
	portal := newFakePortal(t, addr, true)
	m := newPortalManager(t, addr)
	// Stop answering: release the name so calls fail, simulating a hung portal dialog.
	portal.conn.ReleaseName(portalBus)

	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
	defer cancel()
	if _, err := m.Bind(ctx, testShortcuts); err == nil {
		t.Fatal("expected an error")
	}
}
