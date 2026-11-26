package hotkey

import (
	"errors"
	"testing"
)

func TestParse(t *testing.T) {
	tests := []struct {
		in   string
		want Binding
	}{
		{"alt+f10", Binding{Alt, "f10"}},
		{"Alt+F9", Binding{Alt, "f9"}},
		{" ctrl + shift + m ", Binding{Ctrl | Shift, "m"}},
		{"super+1", Binding{Super, "1"}},
		{"cmd+option+space", Binding{Super | Alt, "space"}},
		{"control+enter", Binding{Ctrl, "return"}},
		{"win+esc", Binding{Super, "escape"}},
		{"ctrl+alt+shift+super+f20", Binding{Ctrl | Alt | Shift | Super, "f20"}},
	}
	for _, tt := range tests {
		got, err := Parse(tt.in)
		if err != nil {
			t.Errorf("Parse(%q): %v", tt.in, err)
			continue
		}
		if got != tt.want {
			t.Errorf("Parse(%q) = %+v, want %+v", tt.in, got, tt.want)
		}
	}
}

func TestParseErrors(t *testing.T) {
	for _, in := range []string{
		"",          // nothing
		"f10",       // bare key would be swallowed system wide
		"alt+",      // no key
		"hyper+f10", // unknown modifier
		"alt+f21",   // unsupported key
		"alt+alt+x", // repeated modifier
		"alt+ctrl",  // modifier as key
		"alt+f10+x", // key in modifier position
	} {
		if b, err := Parse(in); err == nil {
			t.Errorf("Parse(%q) = %+v, want error", in, b)
		}
	}
}

func TestStringRoundTrips(t *testing.T) {
	for _, in := range []string{"alt+f10", "ctrl+shift+m", "ctrl+alt+super+space", "shift+return"} {
		b, err := Parse(in)
		if err != nil {
			t.Fatal(err)
		}
		if b.String() != in {
			t.Errorf("String() = %q, want %q", b.String(), in)
		}
		again, _ := Parse(b.String())
		if again != b {
			t.Errorf("round trip of %q changed the binding", in)
		}
	}
}

func TestRegisterWithoutSupportFailsCleanly(t *testing.T) {
	if Available() {
		t.Skip("global hotkeys are built in")
	}
	_, err := Register(Binding{Alt, "f10"})
	if !errors.Is(err, ErrUnavailable) {
		t.Fatalf("got %v, want ErrUnavailable", err)
	}
}

func TestDisplayAndPortalTrigger(t *testing.T) {
	tests := []struct {
		in, display, portal string
	}{
		{"alt+f10", altName + "+F10", "ALT+F10"},
		{"ctrl+shift+m", "Ctrl+Shift+M", "CTRL+SHIFT+m"},
		{"alt+space", altName + "+Space", "ALT+Space"},
		{"ctrl+return", "Ctrl+Return", "CTRL+Return"},
		{"super+1", superName + "+1", "LOGO+1"},
	}
	for _, tt := range tests {
		b, err := Parse(tt.in)
		if err != nil {
			t.Fatal(err)
		}
		if got := b.Display(); got != tt.display {
			t.Errorf("Display(%s) = %q, want %q", tt.in, got, tt.display)
		}
		if got := b.PortalTrigger(); got != tt.portal {
			t.Errorf("PortalTrigger(%s) = %q, want %q", tt.in, got, tt.portal)
		}
	}
}

func TestModifierDisplay(t *testing.T) {
	if got := (Ctrl | Shift).Display(); got != "Ctrl+Shift" {
		t.Errorf("got %q", got)
	}
	if got := Modifier(0).Display(); got != "" {
		t.Errorf("got %q", got)
	}
}

func TestCheckPlatform(t *testing.T) {
	refused := []Binding{{Alt, "f10"}, {Alt | Shift, "c"}}
	allowed := []Binding{{Ctrl | Alt, "f10"}, {Super | Alt, "c"}, {Shift, "f1"}, {Ctrl, "f10"}}
	for _, b := range refused {
		if err := CheckPlatform("darwin", b); err != ErrOptionOnly {
			t.Errorf("darwin should refuse %s, got %v", b, err)
		}
		for _, goos := range []string{"linux", "windows"} {
			if err := CheckPlatform(goos, b); err != nil {
				t.Errorf("%s allows %s, got %v", goos, b, err)
			}
		}
	}
	for _, b := range allowed {
		if err := CheckPlatform("darwin", b); err != nil {
			t.Errorf("darwin allows %s, got %v", b, err)
		}
	}
}
