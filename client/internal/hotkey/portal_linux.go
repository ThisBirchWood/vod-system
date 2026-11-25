package hotkey

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/godbus/dbus/v5"
)

// D-Bus names for the XDG desktop portal's GlobalShortcuts interface, which is how Wayland
// desktops (GNOME 48+, KDE Plasma 5.27+, Hyprland) let apps have global shortcuts.
const (
	portalBus     = "org.freedesktop.portal.Desktop"
	portalPath    = dbus.ObjectPath("/org/freedesktop/portal/desktop")
	shortcutsIntf = "org.freedesktop.portal.GlobalShortcuts"
	requestIntf   = "org.freedesktop.portal.Request"
	sessionIntf   = "org.freedesktop.portal.Session"
	registryIntf  = "org.freedesktop.host.portal.Registry"
)

type portalResponse struct {
	code    uint32
	results map[string]dbus.Variant
}

// PortalManager binds shortcuts through the GlobalShortcuts portal. The desktop shows the user
// the requested combinations and may let them pick different ones.
type PortalManager struct {
	conn    *dbus.Conn
	portal  dbus.BusObject
	sender  string
	signals chan *dbus.Signal
	pressed chan string
	tokens  atomic.Uint64

	mu      sync.Mutex
	session dbus.ObjectPath
	pending map[dbus.ObjectPath]chan portalResponse
}

// NewPortalManager uses conn, which should be a private connection: the app ID registration
// only applies to calls made after it on the same connection. appID must match an installed
// .desktop file for desktops that require one (GNOME).
func NewPortalManager(conn *dbus.Conn, appID string) (*PortalManager, error) {
	portal := conn.Object(portalBus, portalPath)

	// Unsandboxed apps announce their app ID so the desktop can name them in its dialog and
	// remember the user's choices. Older portals lack the registry; that is fine.
	_ = portal.Call(registryIntf+".Register", 0, appID, map[string]dbus.Variant{}).Err

	if _, err := portal.GetProperty(shortcutsIntf + ".version"); err != nil {
		return nil, fmt.Errorf("%w: the desktop has no GlobalShortcuts portal", ErrUnavailable)
	}

	names := conn.Names()
	if len(names) == 0 {
		return nil, errors.New("D-Bus connection has no unique name")
	}
	m := &PortalManager{
		conn:    conn,
		portal:  portal,
		sender:  strings.ReplaceAll(strings.TrimPrefix(names[0], ":"), ".", "_"),
		signals: make(chan *dbus.Signal, 16),
		pressed: make(chan string, 8),
		pending: map[dbus.ObjectPath]chan portalResponse{},
	}
	for _, rule := range [][]dbus.MatchOption{
		{dbus.WithMatchInterface(requestIntf), dbus.WithMatchMember("Response")},
		{dbus.WithMatchInterface(shortcutsIntf), dbus.WithMatchMember("Activated")},
	} {
		if err := conn.AddMatchSignal(rule...); err != nil {
			return nil, err
		}
	}
	conn.Signal(m.signals)
	go m.dispatch()
	return m, nil
}

func (m *PortalManager) dispatch() {
	for sig := range m.signals {
		switch sig.Name {
		case requestIntf + ".Response":
			if len(sig.Body) < 2 {
				continue
			}
			code, _ := sig.Body[0].(uint32)
			results, _ := sig.Body[1].(map[string]dbus.Variant)
			m.mu.Lock()
			ch, ok := m.pending[sig.Path]
			delete(m.pending, sig.Path)
			m.mu.Unlock()
			if ok {
				ch <- portalResponse{code, results}
			}
		case shortcutsIntf + ".Activated":
			if len(sig.Body) < 2 {
				continue
			}
			session, _ := sig.Body[0].(dbus.ObjectPath)
			id, _ := sig.Body[1].(string)
			m.mu.Lock()
			current := m.session
			m.mu.Unlock()
			if session == current {
				select {
				case m.pressed <- id:
				default:
				}
			}
		}
	}
}

// request calls a portal method that answers asynchronously through a Request object.
func (m *PortalManager) request(ctx context.Context, method string, options map[string]dbus.Variant, args ...any) (map[string]dbus.Variant, error) {
	token := fmt.Sprintf("vodclient%d", m.tokens.Add(1))
	options["handle_token"] = dbus.MakeVariant(token)
	// Subscribe before calling, at the path the portal will use, so the reply cannot be missed.
	expected := dbus.ObjectPath(fmt.Sprintf("%s/request/%s/%s", portalPath, m.sender, token))
	ch := make(chan portalResponse, 1)
	m.mu.Lock()
	m.pending[expected] = ch
	m.mu.Unlock()
	forget := func(p dbus.ObjectPath) {
		m.mu.Lock()
		delete(m.pending, p)
		m.mu.Unlock()
	}

	var handle dbus.ObjectPath
	call := m.portal.CallWithContext(ctx, shortcutsIntf+"."+method, 0, append(args, options)...)
	if err := call.Store(&handle); err != nil {
		forget(expected)
		return nil, fmt.Errorf("%s: %w", method, err)
	}
	if handle != expected {
		// Portals before 0.9 picked their own path; follow it.
		m.mu.Lock()
		delete(m.pending, expected)
		m.pending[handle] = ch
		m.mu.Unlock()
	}

	select {
	case resp := <-ch:
		switch resp.code {
		case 0:
			return resp.results, nil
		case 1:
			return nil, ErrCancelled
		default:
			return nil, fmt.Errorf("%s failed (response %d)", method, resp.code)
		}
	case <-ctx.Done():
		forget(handle)
		return nil, ctx.Err()
	}
}

type portalShortcut struct {
	ID    string
	Props map[string]dbus.Variant
}

// Bind starts a fresh portal session and binds shortcuts in it. Portals allow one bind per
// session, so changing shortcuts replaces the session.
func (m *PortalManager) Bind(ctx context.Context, shortcuts []Shortcut) (map[string]string, error) {
	m.closeSession()

	results, err := m.request(ctx, "CreateSession", map[string]dbus.Variant{
		"session_handle_token": dbus.MakeVariant(fmt.Sprintf("vodclient%d", m.tokens.Add(1))),
	})
	if err != nil {
		return nil, err
	}
	session, err := objectPath(results["session_handle"])
	if err != nil {
		return nil, fmt.Errorf("CreateSession: %w", err)
	}
	m.mu.Lock()
	m.session = session
	m.mu.Unlock()

	req := make([]portalShortcut, 0, len(shortcuts))
	for _, s := range shortcuts {
		req = append(req, portalShortcut{ID: s.ID, Props: map[string]dbus.Variant{
			"description":       dbus.MakeVariant(s.Description),
			"preferred_trigger": dbus.MakeVariant(s.Binding.PortalTrigger()),
		}})
	}
	results, err = m.request(ctx, "BindShortcuts", map[string]dbus.Variant{}, session, req, "")
	if err != nil {
		return nil, err
	}
	return parseBound(results["shortcuts"]), nil
}

func objectPath(v dbus.Variant) (dbus.ObjectPath, error) {
	switch p := v.Value().(type) {
	case dbus.ObjectPath:
		return p, nil
	case string:
		return dbus.ObjectPath(p), nil
	default:
		return "", errors.New("no session handle in response")
	}
}

// parseBound reads the a(sa{sv}) list of bound shortcuts into ID → trigger description.
func parseBound(v dbus.Variant) map[string]string {
	bound := map[string]string{}
	list, _ := v.Value().([][]any)
	for _, entry := range list {
		if len(entry) < 2 {
			continue
		}
		id, _ := entry[0].(string)
		props, _ := entry[1].(map[string]dbus.Variant)
		desc, _ := props["trigger_description"].Value().(string)
		if id != "" {
			bound[id] = desc
		}
	}
	return bound
}

func (m *PortalManager) closeSession() {
	m.mu.Lock()
	session := m.session
	m.session = ""
	m.mu.Unlock()
	if session != "" {
		m.conn.Object(portalBus, session).Call(sessionIntf+".Close", 0)
	}
}

func (m *PortalManager) Pressed() <-chan string { return m.pressed }
func (m *PortalManager) AppManaged() bool       { return false }

func (m *PortalManager) Close() error {
	m.closeSession()
	m.conn.RemoveSignal(m.signals)
	return m.conn.Close()
}
