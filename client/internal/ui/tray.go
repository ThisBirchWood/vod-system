package ui

import (
	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/driver/desktop"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/app"
)

// trayMenu is the system tray menu, ShadowPlay's overlay in menu form.
type trayMenu struct {
	menu    *fyne.Menu
	status  *fyne.MenuItem
	clip    *fyne.MenuItem
	section *fyne.MenuItem
	marker  *fyne.MenuItem
	live    *fyne.MenuItem
	isLive  bool
}

func newTrayMenu(a fyne.App, u *UI) *trayMenu {
	t := &trayMenu{}
	t.status = fyne.NewMenuItem("Starting…", nil)
	t.status.Disabled = true
	t.clip = fyne.NewMenuItem("Save clip", u.ctl.Clip)
	t.section = fyne.NewMenuItem("Start section", u.ctl.ToggleSection)
	t.marker = fyne.NewMenuItem("Add marker", u.ctl.Mark)
	t.live = fyne.NewMenuItem("Go live", func() { u.ctl.SetLive(!t.isLive) })
	open := fyne.NewMenuItem("Open VoD Client", u.ShowWindow)
	quit := fyne.NewMenuItem("Quit", nil)
	quit.IsQuit = true // the driver wires it to App.Quit, which runs the shutdown hook

	t.menu = fyne.NewMenu("VoD Client",
		t.status,
		fyne.NewMenuItemSeparator(),
		t.clip, t.section, t.marker,
		fyne.NewMenuItemSeparator(),
		t.live, open,
		fyne.NewMenuItemSeparator(),
		quit,
	)
	if d, ok := a.(desktop.App); ok {
		d.SetSystemTrayMenu(t.menu)
		d.SetSystemTrayIcon(Icon)
	}
	return t
}

func (t *trayMenu) update(s app.Status) {
	ready := s.Phase == app.PhaseReady
	for _, item := range []*fyne.MenuItem{t.clip, t.section, t.marker, t.live} {
		item.Disabled = !ready
	}
	t.isLive = s.Live

	switch {
	case ready && s.Live:
		t.status.Label = "● Live"
	case ready:
		t.status.Label = "Offline"
	case s.Phase == app.PhaseError:
		t.status.Label = "Needs attention: open VoD Client"
	default:
		t.status.Label = "Setting up…"
	}
	if s.Section {
		t.section.Label = "Save section"
	} else {
		t.section.Label = "Start section"
	}
	if s.Live {
		t.live.Label = "Stop streaming"
	} else {
		t.live.Label = "Go live"
	}
	t.clip.Label = labelWithKey("Save clip", s.Hotkeys[app.ActionClip])
	t.marker.Label = labelWithKey("Add marker", s.Hotkeys[app.ActionMarker])
	t.menu.Refresh()
}

func labelWithKey(label, key string) string {
	if key == "" {
		return label
	}
	return label + "  (" + key + ")"
}
