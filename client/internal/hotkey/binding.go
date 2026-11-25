// Package hotkey parses key combinations and registers them as global hotkeys.
package hotkey

import (
	"errors"
	"fmt"
	"slices"
	"strings"
)

// Modifier is a set of modifier keys.
type Modifier uint8

const (
	Ctrl Modifier = 1 << iota
	Shift
	Alt
	// Super is the Windows key on Windows/Linux and Command on macOS.
	Super
)

var modifierNames = map[string]Modifier{
	"ctrl": Ctrl, "control": Ctrl,
	"shift": Shift,
	"alt":   Alt, "option": Alt, "opt": Alt,
	"super": Super, "win": Super, "meta": Super, "cmd": Super, "command": Super,
}

// keyAliases maps alternative spellings to canonical key names.
var keyAliases = map[string]string{
	"enter": "return", "esc": "escape", "del": "delete",
}

// Binding is a parsed key combination such as alt+f10.
type Binding struct {
	Mods Modifier
	// Key is the canonical lowercase key name, e.g. "f10", "s", "space".
	Key string
}

// Parse reads a combination like "Alt+F10" or "ctrl+shift+m". At least one modifier is required:
// a bare key would be swallowed system wide.
func Parse(s string) (Binding, error) {
	parts := strings.Split(strings.ToLower(strings.TrimSpace(s)), "+")
	if len(parts) < 2 {
		return Binding{}, fmt.Errorf("hotkey %q needs at least one modifier and a key, e.g. alt+f10", s)
	}

	var b Binding
	for _, p := range parts[:len(parts)-1] {
		m, ok := modifierNames[strings.TrimSpace(p)]
		if !ok {
			return Binding{}, fmt.Errorf("hotkey %q: unknown modifier %q", s, p)
		}
		if b.Mods&m != 0 {
			return Binding{}, fmt.Errorf("hotkey %q: modifier %q repeated", s, p)
		}
		b.Mods |= m
	}

	key := strings.TrimSpace(parts[len(parts)-1])
	if alias, ok := keyAliases[key]; ok {
		key = alias
	}
	if !slices.Contains(keyNames, key) {
		return Binding{}, fmt.Errorf("hotkey %q: unsupported key %q", s, key)
	}
	b.Key = key
	return b, nil
}

// String formats the binding canonically, e.g. "ctrl+alt+f10".
func (b Binding) String() string {
	var parts []string
	for _, m := range []struct {
		mod  Modifier
		name string
	}{{Ctrl, "ctrl"}, {Shift, "shift"}, {Alt, "alt"}, {Super, "super"}} {
		if b.Mods&m.mod != 0 {
			parts = append(parts, m.name)
		}
	}
	return strings.Join(append(parts, b.Key), "+")
}

// keyNames are the keys every supported platform can register.
var keyNames = func() []string {
	names := []string{"space", "return", "escape", "delete", "tab", "left", "right", "up", "down"}
	for c := 'a'; c <= 'z'; c++ {
		names = append(names, string(c))
	}
	for c := '0'; c <= '9'; c++ {
		names = append(names, string(c))
	}
	for i := 1; i <= 20; i++ {
		names = append(names, fmt.Sprintf("f%d", i))
	}
	return names
}()

// ErrUnavailable means global hotkeys cannot be registered in this build or session.
var ErrUnavailable = errors.New("global hotkeys are unavailable")

// Registration is a registered global hotkey.
type Registration interface {
	// Pressed delivers a value each time the hotkey is pressed.
	Pressed() <-chan struct{}
	Unregister() error
}
