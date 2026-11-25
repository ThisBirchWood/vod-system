package ui

import (
	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/app"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/config"
)

// clipLengths are the choices offered for the clip length, in seconds.
var clipLengths = []float64{15, 30, 60, 90, 120, 180}

// settingsForm holds the settings dialog's widgets, so tests can drive them.
type settingsForm struct {
	clipLength   *widget.Select
	clipKey      *HotkeyEntry
	sectionKey   *HotkeyEntry
	markerKey    *HotkeyEntry
	startOnLogin *widget.Check
	autoLive     *widget.Check
	closeOBS     *widget.Check
	content      fyne.CanvasObject
}

func (u *UI) newSettingsForm() *settingsForm {
	cfg := u.ctl.Config()
	f := &settingsForm{}

	options := make([]string, len(clipLengths))
	for i, s := range clipLengths {
		options[i] = app.FormatClipLength(s)
	}
	f.clipLength = widget.NewSelect(options, nil)
	f.clipLength.SetSelected(app.FormatClipLength(cfg.ClipSeconds))
	if f.clipLength.Selected == "" {
		f.clipLength.SetSelected(app.FormatClipLength(30))
	}

	f.startOnLogin = widget.NewCheck("Open when my computer starts", nil)
	f.startOnLogin.SetChecked(cfg.StartOnLogin)
	f.autoLive = widget.NewCheck("Go live automatically", nil)
	f.autoLive.SetChecked(cfg.OBS.AutoStartStream)
	f.closeOBS = widget.NewCheck("Close OBS when I quit", nil)
	f.closeOBS.SetChecked(cfg.OBS.CloseOnExit)

	items := []*widget.FormItem{
		{Text: "Clip length", Widget: f.clipLength, HintText: "How far back a saved clip reaches"},
	}

	if u.ctl.HotkeysAppManaged() {
		f.clipKey = NewHotkeyEntry(cfg.Hotkeys.Clip)
		f.sectionKey = NewHotkeyEntry(cfg.Hotkeys.Section)
		f.markerKey = NewHotkeyEntry(cfg.Hotkeys.Marker)
		items = append(items,
			&widget.FormItem{Text: "Save clip", Widget: f.clipKey, HintText: "Click a hotkey, then press new keys"},
			&widget.FormItem{Text: "Section", Widget: f.sectionKey},
			&widget.FormItem{Text: "Add marker", Widget: f.markerKey},
		)
	} else {
		note := widget.NewLabel(wrapText("Your desktop manages VoD Client's shortcuts. Change them in your system's keyboard settings.", 36))
		again := widget.NewButton("Ask for shortcuts again", u.ctl.RebindHotkeys)
		items = append(items, &widget.FormItem{Text: "Hotkeys", Widget: container.NewVBox(note, again)})
	}

	signOut := widget.NewButtonWithIcon("Sign out", theme.LogoutIcon(), func() {
		dialog.ShowConfirm("Sign out?", "Streaming will stop until you sign in again.", func(ok bool) {
			if ok {
				u.ctl.SignOut()
			}
		}, u.win)
	})
	account := container.NewVBox(caption("Signed in as "+u.ctl.Status().User), signOut)

	items = append(items,
		&widget.FormItem{Text: "Startup", Widget: container.NewVBox(f.startOnLogin, f.autoLive, f.closeOBS)},
		&widget.FormItem{Text: "Account", Widget: account},
		&widget.FormItem{Text: "Version", Widget: caption(config.Version + " · " + hostOf(cfg.ServerURL))},
	)
	f.content = widget.NewForm(items...)
	return f
}

// apply copies the form into cfg.
func (f *settingsForm) apply(cfg *config.Config) {
	for i, label := range f.clipLength.Options {
		if label == f.clipLength.Selected {
			cfg.ClipSeconds = clipLengths[i]
		}
	}
	if f.clipKey != nil {
		cfg.Hotkeys.Clip = f.clipKey.Combo()
		cfg.Hotkeys.Section = f.sectionKey.Combo()
		cfg.Hotkeys.Marker = f.markerKey.Combo()
	}
	cfg.StartOnLogin = f.startOnLogin.Checked
	cfg.OBS.AutoStartStream = f.autoLive.Checked
	cfg.OBS.CloseOnExit = f.closeOBS.Checked
}

func (u *UI) showSettings() {
	f := u.newSettingsForm()
	d := dialog.NewCustomConfirm("Settings", "Save", "Cancel", f.content, func(save bool) {
		if !save {
			return
		}
		if err := u.ctl.UpdateSettings(f.apply); err != nil {
			dialog.ShowError(err, u.win)
		}
	}, u.win)
	d.Show()
}
