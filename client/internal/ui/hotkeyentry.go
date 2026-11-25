package ui

import (
	"strings"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/driver/desktop"
	"fyne.io/fyne/v2/widget"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/hotkey"
)

// HotkeyEntry records a key combination: click it, then press the keys. Escape cancels and
// Backspace clears the hotkey.
type HotkeyEntry struct {
	widget.BaseWidget

	// OnChanged is called with the new canonical combination ("" when cleared).
	OnChanged func(string)

	combo     string
	recording bool
	held      hotkey.Modifier
	button    *widget.Button
}

// NewHotkeyEntry returns an entry showing combo (e.g. "alt+f10").
func NewHotkeyEntry(combo string) *HotkeyEntry {
	h := &HotkeyEntry{combo: combo}
	h.button = widget.NewButton("", h.startRecording)
	h.ExtendBaseWidget(h)
	h.refreshLabel()
	return h
}

// Combo returns the current combination in canonical form, or "" if none.
func (h *HotkeyEntry) Combo() string { return h.combo }

// Recording reports whether the entry is waiting for keys.
func (h *HotkeyEntry) Recording() bool { return h.recording }

func (h *HotkeyEntry) CreateRenderer() fyne.WidgetRenderer {
	return widget.NewSimpleRenderer(h.button)
}

func (h *HotkeyEntry) startRecording() {
	h.recording, h.held = true, 0
	h.refreshLabel()
	if c := fyne.CurrentApp().Driver().CanvasForObject(h); c != nil {
		c.Focus(h)
	}
}

func (h *HotkeyEntry) stopRecording() {
	h.recording, h.held = false, 0
	h.refreshLabel()
	if c := fyne.CurrentApp().Driver().CanvasForObject(h); c != nil && c.Focused() == h {
		c.Unfocus()
	}
}

func (h *HotkeyEntry) refreshLabel() {
	switch {
	case h.recording && h.held != 0:
		h.button.SetText(h.held.Display() + "+…")
	case h.recording:
		h.button.SetText("Press a key combination…")
	case h.combo == "":
		h.button.SetText("Not set")
	default:
		b, err := hotkey.Parse(h.combo)
		if err != nil {
			h.button.SetText(h.combo)
		} else {
			h.button.SetText(b.Display())
		}
	}
	if h.recording {
		h.button.Importance = widget.HighImportance
	} else {
		h.button.Importance = widget.MediumImportance
	}
	h.button.Refresh()
}

var modifierKeys = map[fyne.KeyName]hotkey.Modifier{
	desktop.KeyControlLeft: hotkey.Ctrl, desktop.KeyControlRight: hotkey.Ctrl,
	desktop.KeyShiftLeft: hotkey.Shift, desktop.KeyShiftRight: hotkey.Shift,
	desktop.KeyAltLeft: hotkey.Alt, desktop.KeyAltRight: hotkey.Alt,
	desktop.KeySuperLeft: hotkey.Super, desktop.KeySuperRight: hotkey.Super,
}

// KeyDown implements desktop.Keyable: it tracks held modifiers and finishes on the first other key.
func (h *HotkeyEntry) KeyDown(e *fyne.KeyEvent) {
	if !h.recording {
		return
	}
	if m, ok := modifierKeys[e.Name]; ok {
		h.held |= m
		h.refreshLabel()
		return
	}
	switch e.Name {
	case fyne.KeyEscape:
		h.stopRecording()
		return
	case fyne.KeyBackspace:
		if h.held == 0 {
			h.set("")
			return
		}
	}
	b, err := hotkey.Parse(comboString(h.held, e.Name))
	if err != nil {
		// Needs a modifier, or a key every platform supports; keep listening.
		h.held = 0
		h.button.SetText("Use a modifier like Alt or Ctrl, plus a key")
		return
	}
	h.set(b.String())
}

// KeyUp implements desktop.Keyable.
func (h *HotkeyEntry) KeyUp(e *fyne.KeyEvent) {
	if m, ok := modifierKeys[e.Name]; ok && h.recording {
		h.held &^= m
		h.refreshLabel()
	}
}

func (h *HotkeyEntry) set(combo string) {
	changed := combo != h.combo
	h.combo = combo
	h.stopRecording()
	if changed && h.OnChanged != nil {
		h.OnChanged(combo)
	}
}

func comboString(mods hotkey.Modifier, key fyne.KeyName) string {
	parts := []string{}
	for _, m := range []struct {
		mod  hotkey.Modifier
		name string
	}{{hotkey.Ctrl, "ctrl"}, {hotkey.Shift, "shift"}, {hotkey.Alt, "alt"}, {hotkey.Super, "super"}} {
		if mods&m.mod != 0 {
			parts = append(parts, m.name)
		}
	}
	// Fyne's key names ("F10", "A", "Space", "Return") lowercase to ours.
	return strings.Join(append(parts, strings.ToLower(string(key))), "+")
}

// Focusable: the entry only takes keys while recording.
func (h *HotkeyEntry) FocusGained()                {}
func (h *HotkeyEntry) FocusLost()                  { h.recording, h.held = false, 0; h.refreshLabel() }
func (h *HotkeyEntry) TypedRune(rune)              {}
func (h *HotkeyEntry) TypedKey(*fyne.KeyEvent)     {}
func (h *HotkeyEntry) TypedShortcut(fyne.Shortcut) {}
