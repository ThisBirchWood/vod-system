package ui

import (
	imgcolor "image/color"
	"image/png"
	"os"
	"path/filepath"
	"testing"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/test"
	"fyne.io/fyne/v2/theme"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/app"
)

type variantTheme struct {
	Theme
	v fyne.ThemeVariant
}

func (t variantTheme) Color(n fyne.ThemeColorName, _ fyne.ThemeVariant) imgcolor.Color {
	return t.Theme.Color(n, t.v)
}

// TestScreens renders every screen, in light and dark mode, to PNG files for visual review:
//
//	SHOTS=/tmp/shots go test -run TestScreens ./internal/ui
func TestScreens(t *testing.T) {
	out := os.Getenv("SHOTS")
	if out == "" {
		t.Skip()
	}
	ready := readyStatus()
	ready.Activity = []app.Activity{
		{Time: time.Now(), Title: "Clip saved", Body: "Clip 2026-11-25 20:31:12"},
		{Time: time.Now(), Title: "Marker added", Body: "Marker 2026-11-25 20:29:40"},
		{Time: time.Now(), Title: "Section saved", Body: "Section 2026-11-25 20:10:02"},
	}
	shots := map[string]app.Status{
		"signedout": {Phase: app.PhaseSignedOut},
		"needsobs":  {Phase: app.PhaseNeedsOBS, Message: "VoD Client uses OBS Studio, a free recording app, to capture your screen."},
		"error":     {Phase: app.PhaseError, Message: "OBS was closed. Click Retry to start it again."},
		"starting":  {Phase: app.PhaseStartingOBS, Message: "Starting OBS…"},
		"ready":     ready,
	}
	for _, variant := range []fyne.ThemeVariant{theme.VariantLight, theme.VariantDark} {
		for name, s := range shots {
			f := newFake()
			a := test.NewTempApp(t)
			u := New(a, f, Options{})
			a.Settings().SetTheme(variantTheme{v: variant})
			u.win.Resize(fyne.NewSize(windowWidth, 640))
			f.publish(s)
			if name == "ready" && variant == theme.VariantLight {
				s2 := s
				s2.HotkeyProblem = ""
				f.publish(s2)
			}
			img := u.win.Canvas().Capture()
			file, _ := os.Create(filepath.Join(out, name+map[fyne.ThemeVariant]string{0: "-dark", 1: "-light"}[variant]+".png"))
			png.Encode(file, img)
			file.Close()
		}
	}
	// Settings dialog.
	f := newFake()
	a := test.NewTempApp(t)
	u := New(a, f, Options{})
	u.win.Resize(fyne.NewSize(windowWidth, 640))
	f.publish(readyStatus())
	u.showSettings()
	file, _ := os.Create(filepath.Join(out, "settings-light.png"))
	png.Encode(file, u.win.Canvas().Capture())
	file.Close()
}
