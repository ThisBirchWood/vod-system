// Package ui is the desktop interface: a small window that walks the user through setup and then
// shows their live status and recent captures, plus a tray menu for when the window is closed.
package ui

import (
	"context"
	"net/url"
	"strings"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/layout"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/app"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/config"
)

// Controller is what the UI drives; *app.Controller implements it.
type Controller interface {
	Status() app.Status
	Subscribe(func(app.Status))
	Config() config.Config
	HotkeysAppManaged() bool
	SetServer(ctx context.Context, url string) error
	SignIn()
	CancelSignIn()
	SignOut()
	InstallOBS()
	Retry()
	SetLive(bool)
	Clip()
	Mark()
	ToggleSection()
	RebindHotkeys()
	UpdateSettings(func(*config.Config)) error
}

// Options configure the UI.
type Options struct {
	// TrayAvailable lets closing the window hide it instead of quitting.
	TrayAvailable bool
	// StartHidden starts in the tray, e.g. when launched at login.
	StartHidden bool
}

// UI is the app's window and tray menu.
type UI struct {
	app  fyne.App
	win  fyne.Window
	ctl  Controller
	opts Options

	// async runs slow controller calls off the UI thread; tests make it synchronous.
	async func(func())

	phase      app.Phase
	message    string
	ready      *readyView
	tray       *trayMenu
	toldInTray bool
}

// New builds the UI and subscribes it to the controller.
func New(a fyne.App, ctl Controller, opts Options) *UI {
	a.Settings().SetTheme(Theme{})
	a.SetIcon(Icon)

	u := &UI{app: a, ctl: ctl, opts: opts, phase: -1, async: func(f func()) { go f() }}
	u.win = a.NewWindow(config.AppName)
	u.win.SetIcon(Icon)
	u.win.Resize(fyne.NewSize(windowWidth, 640))
	u.win.CenterOnScreen()
	u.win.SetCloseIntercept(u.onClose)

	if opts.TrayAvailable {
		u.tray = newTrayMenu(a, u)
	}
	ctl.Subscribe(func(s app.Status) { fyne.Do(func() { u.render(s) }) })
	u.render(ctl.Status())
	return u
}

// Window returns the main window.
func (u *UI) Window() fyne.Window { return u.win }

// Run shows the window (unless starting hidden) and runs the app until it quits.
func (u *UI) Run() {
	if !u.opts.StartHidden || !u.opts.TrayAvailable {
		u.win.Show()
	}
	u.app.Run()
}

// ShowWindow brings the window to the front, e.g. when the app is launched a second time.
func (u *UI) ShowWindow() {
	u.win.Show()
	u.win.RequestFocus()
}

func (u *UI) onClose() {
	if u.opts.TrayAvailable {
		u.win.Hide()
		if !u.toldInTray {
			u.toldInTray = true
			u.app.SendNotification(fyne.NewNotification(config.AppName,
				"Still running in the background, so your hotkeys keep working. Use the tray icon to open or quit."))
		}
		return
	}
	dialog.ShowConfirm("Quit VoD Client?",
		"Your hotkeys will stop working until you open VoD Client again.",
		func(ok bool) {
			if ok {
				u.app.Quit()
			}
		}, u.win)
}

// render shows the view for the status. Views are rebuilt only when the phase or its message
// changes; the ready view updates in place so buttons don't flicker.
func (u *UI) render(s app.Status) {
	if u.tray != nil {
		u.tray.update(s)
	}
	if s.Phase == u.phase && s.Message == u.message {
		if u.ready != nil {
			u.ready.update(s)
		}
		return
	}
	u.phase, u.message = s.Phase, s.Message
	u.ready = nil

	var content fyne.CanvasObject
	switch s.Phase {
	case app.PhaseLoading:
		content = u.progressView("Connecting…", "")
	case app.PhaseNeedsServer:
		content = u.serverView()
	case app.PhaseSignedOut:
		content = u.signedOutView(s.Message)
	case app.PhaseSigningIn:
		content = u.signingInView()
	case app.PhaseNeedsOBS:
		content = u.needsOBSView(s.Message)
	case app.PhaseInstallingOBS:
		content = u.progressView("Installing OBS Studio", s.Message)
	case app.PhaseStartingOBS:
		content = u.progressView("Getting ready", s.Message)
	case app.PhaseError:
		content = u.errorView(s.Message)
	case app.PhaseReady:
		u.ready = newReadyView(u)
		u.ready.update(s)
		content = u.ready.content
	}
	u.win.SetContent(content)
}

// --- setup views ---

func appIcon(size float32) fyne.CanvasObject {
	img := canvas.NewImageFromResource(Icon)
	img.FillMode = canvas.ImageFillContain
	img.SetMinSize(fyne.NewSquareSize(size))
	return container.NewCenter(img)
}

func heading(text string) *widget.Label {
	l := widget.NewLabelWithStyle(text, fyne.TextAlignCenter, fyne.TextStyle{Bold: true})
	l.SizeName = theme.SizeNameSubHeadingText
	return l
}

// windowWidth and the wrap widths below go together: text is wrapped by hand because Fyne
// under-measures word-wrapped labels inside vertical boxes, letting them overlap what follows.
const (
	windowWidth   = 480
	paragraphWrap = 52
	activityWrap  = 46
)

func paragraph(text string) *widget.Label {
	return widget.NewLabelWithStyle(wrapText(text, paragraphWrap), fyne.TextAlignCenter, fyne.TextStyle{})
}

// wrapText breaks text into lines of at most width characters at word boundaries.
func wrapText(text string, width int) string {
	var out []string
	for _, para := range strings.Split(text, "\n") {
		line := ""
		for _, word := range strings.Fields(para) {
			switch {
			case line == "":
				line = word
			case len([]rune(line))+1+len([]rune(word)) <= width:
				line += " " + word
			default:
				out = append(out, line)
				line = word
			}
		}
		out = append(out, line)
	}
	return strings.Join(out, "\n")
}

func problem(text string) *widget.Label {
	l := paragraph(text)
	l.Importance = widget.DangerImportance
	return l
}

// setWrapped sets a hand-wrapped label's text.
func setWrapped(l *widget.Label, text string) { l.SetText(wrapText(text, paragraphWrap)) }

func primaryButton(label string, icon fyne.Resource, tapped func()) *widget.Button {
	b := widget.NewButtonWithIcon(label, icon, tapped)
	b.Importance = widget.HighImportance
	return b
}

// page centres a setup step in the window with comfortable margins.
func page(objects ...fyne.CanvasObject) fyne.CanvasObject {
	body := container.NewVBox(objects...)
	return container.NewPadded(container.NewPadded(
		container.NewVBox(layout.NewSpacer(), body, layout.NewSpacer())))
}

func (u *UI) progressView(title, message string) fyne.CanvasObject {
	activity := widget.NewActivity()
	activity.Start()
	return page(appIcon(96), heading(title), container.NewCenter(activity), paragraph(message))
}

func (u *UI) serverView() fyne.CanvasObject {
	entry := widget.NewEntry()
	entry.SetPlaceHolder("vod.example.com")
	status := problem("")
	var submit *widget.Button
	submit = primaryButton("Continue", theme.NavigateNextIcon(), func() {
		submit.Disable()
		status.SetText("")
		addr := entry.Text
		u.async(func() {
			err := u.ctl.SetServer(context.Background(), addr)
			fyne.Do(func() {
				submit.Enable()
				if err != nil {
					setWrapped(status, capitalise(err.Error()))
				}
			})
		})
	})
	entry.OnSubmitted = func(string) { submit.OnTapped() }
	return page(appIcon(96), heading("Welcome to VoD Client"),
		paragraph("Enter the address of your VoD server. You'll only need to do this once."),
		entry, submit, status)
}

func (u *UI) signedOutView(message string) fyne.CanvasObject {
	objects := []fyne.CanvasObject{
		appIcon(112),
		heading("Welcome to VoD Client"),
		paragraph("Save the best moments of your gameplay with a single key press."),
		primaryButton("Sign in with Google", theme.LoginIcon(), u.ctl.SignIn),
	}
	if message != "" {
		objects = append(objects, problem(message))
	}
	server := u.ctl.Config().ServerURL
	if config.DefaultServerURL == "" || server != config.DefaultServerURL {
		// Only self-hosters who picked a server need to see or change it.
		change := widget.NewButton("Change server", u.changeServer)
		change.Importance = widget.LowImportance
		objects = append(objects, widget.NewSeparator(), caption("Server: "+hostOf(server)), change)
	}
	return page(objects...)
}

func (u *UI) signingInView() fyne.CanvasObject {
	activity := widget.NewActivity()
	activity.Start()
	cancel := widget.NewButton("Cancel", u.ctl.CancelSignIn)
	return page(appIcon(96), heading("Continue in your browser"),
		paragraph("We've opened your browser so you can sign in with Google. Come back here when you're done."),
		container.NewCenter(activity), cancel)
}

func (u *UI) needsOBSView(message string) fyne.CanvasObject {
	retry := widget.NewButton("I've installed it myself", u.ctl.Retry)
	retry.Importance = widget.LowImportance
	return page(appIcon(96), heading("One more step"), paragraph(message),
		paragraph("VoD Client will install and set it up for you. Your computer may ask for permission."),
		primaryButton("Install OBS Studio", theme.DownloadIcon(), u.ctl.InstallOBS), retry)
}

func (u *UI) errorView(message string) fyne.CanvasObject {
	icon := widget.NewIcon(theme.WarningIcon())
	iconBox := container.NewCenter(container.NewGridWrap(fyne.NewSquareSize(64), icon))
	signOut := widget.NewButton("Sign out", u.ctl.SignOut)
	signOut.Importance = widget.LowImportance
	return page(iconBox, heading("Something went wrong"), paragraph(message),
		primaryButton("Retry", theme.ViewRefreshIcon(), u.ctl.Retry), signOut)
}

func (u *UI) changeServer() {
	entry := widget.NewEntry()
	entry.SetText(u.ctl.Config().ServerURL)
	status := problem("")
	content := container.NewVBox(paragraph("Enter the address of your VoD server."), entry, status)
	var d *dialog.ConfirmDialog
	d = dialog.NewCustomConfirm("Change server", "Connect", "Cancel", content, func(ok bool) {
		if !ok {
			return
		}
		addr := entry.Text
		u.async(func() {
			if err := u.ctl.SetServer(context.Background(), addr); err != nil {
				fyne.Do(func() {
					setWrapped(status, capitalise(err.Error()))
					d.Show()
				})
			}
		})
	}, u.win)
	d.Show()
}

// --- ready view ---

// readyView is the main screen once everything is set up.
type readyView struct {
	ui      *UI
	content fyne.CanvasObject

	user        *widget.Label
	liveDot     *canvas.Circle
	liveLabel   *canvas.Text
	liveButton  *widget.Button
	banner      *fyne.Container
	bannerText  *widget.Label
	retryKeys   *widget.Button
	clip        *widget.Button
	section     *widget.Button
	marker      *widget.Button
	clipKey     *widget.Label
	sectionKey  *widget.Label
	markerKey   *widget.Label
	activity    *fyne.Container
	emptyNote   *widget.Label
	website     *widget.Hyperlink
	live        bool
	activityTop time.Time
	activityLen int
}

func caption(text string) *widget.Label {
	l := widget.NewLabel(text)
	l.SizeName = theme.SizeNameCaptionText
	l.Importance = widget.LowImportance
	return l
}

func newReadyView(u *UI) *readyView {
	v := &readyView{ui: u}

	title := widget.NewLabelWithStyle(config.AppName, fyne.TextAlignLeading, fyne.TextStyle{Bold: true})
	title.SizeName = theme.SizeNameSubHeadingText
	v.user = caption("")
	icon := canvas.NewImageFromResource(Icon)
	icon.FillMode = canvas.ImageFillContain
	icon.SetMinSize(fyne.NewSquareSize(44))

	v.liveDot = canvas.NewCircle(liveRed)
	v.liveLabel = canvas.NewText("LIVE", liveRed)
	v.liveLabel.TextStyle = fyne.TextStyle{Bold: true}
	v.liveLabel.TextSize = theme.CaptionTextSize()
	dot := container.NewGridWrap(fyne.NewSquareSize(10), v.liveDot)
	badge := container.NewHBox(container.NewCenter(dot), v.liveLabel)
	v.liveButton = widget.NewButton("", func() { u.ctl.SetLive(!v.live) })

	header := container.NewBorder(nil, nil,
		container.NewHBox(icon, container.NewVBox(title, v.user)),
		container.NewVBox(container.NewCenter(badge), v.liveButton))

	v.bannerText = widget.NewLabel("")
	v.bannerText.Importance = widget.WarningImportance
	v.retryKeys = widget.NewButton("Set up hotkeys again", u.ctl.RebindHotkeys)
	v.banner = container.NewVBox(v.bannerText, v.retryKeys)

	row := func(btn *widget.Button, key *widget.Label) fyne.CanvasObject {
		return container.NewBorder(nil, nil, nil, key, btn)
	}
	v.clip = primaryButton("Save clip", theme.ContentCutIcon(), u.ctl.Clip)
	v.section = widget.NewButtonWithIcon("Start section", theme.MediaRecordIcon(), u.ctl.ToggleSection)
	v.marker = widget.NewButtonWithIcon("Add marker", theme.ConfirmIcon(), u.ctl.Mark)
	v.clipKey, v.sectionKey, v.markerKey = keyLabel(), keyLabel(), keyLabel()

	v.activity = container.NewVBox()
	v.emptyNote = caption("Your clips, sections and markers will show up here.")
	recentTitle := widget.NewLabelWithStyle("Recent activity", fyne.TextAlignLeading, fyne.TextStyle{Bold: true})

	settings := widget.NewButtonWithIcon("Settings", theme.SettingsIcon(), u.showSettings)
	settings.Importance = widget.LowImportance
	v.website = widget.NewHyperlink("Watch your clips online", nil)
	v.website.Hide()

	top := container.NewVBox(header, widget.NewSeparator(), v.banner,
		row(v.clip, v.clipKey), row(v.section, v.sectionKey), row(v.marker, v.markerKey),
		widget.NewSeparator(), recentTitle)
	footer := container.NewBorder(nil, nil, settings, v.website)
	list := container.NewVScroll(container.NewVBox(v.emptyNote, v.activity))
	v.content = container.NewPadded(container.NewBorder(top, footer, nil, nil, list))
	return v
}

func keyLabel() *widget.Label {
	l := widget.NewLabelWithStyle("", fyne.TextAlignTrailing, fyne.TextStyle{Monospace: true})
	l.Importance = widget.LowImportance
	return l
}

func (v *readyView) update(s app.Status) {
	v.user.SetText("Signed in as " + s.User)

	v.live = s.Live
	if s.Live {
		v.liveDot.FillColor, v.liveLabel.Color, v.liveLabel.Text = liveRed, liveRed, "LIVE"
		v.liveButton.SetText("Stop streaming")
		v.liveButton.Importance = widget.MediumImportance
	} else {
		grey := theme.Color(theme.ColorNamePlaceHolder)
		v.liveDot.FillColor, v.liveLabel.Color, v.liveLabel.Text = grey, grey, "OFFLINE"
		v.liveButton.SetText("Go live")
		v.liveButton.Importance = widget.HighImportance
	}
	v.liveDot.Refresh()
	v.liveLabel.Refresh()
	v.liveButton.Refresh()

	if s.Section {
		v.section.SetText("Save section")
		v.section.SetIcon(theme.MediaStopIcon())
		v.section.Importance = widget.DangerImportance
	} else {
		v.section.SetText("Start section")
		v.section.SetIcon(theme.MediaRecordIcon())
		v.section.Importance = widget.MediumImportance
	}
	v.section.Refresh()

	v.clipKey.SetText(s.Hotkeys[app.ActionClip])
	v.sectionKey.SetText(s.Hotkeys[app.ActionSection])
	v.markerKey.SetText(s.Hotkeys[app.ActionMarker])

	if s.HotkeyProblem != "" {
		v.bannerText.SetText(wrapText(s.HotkeyProblem, paragraphWrap))
		v.banner.Show()
		if strings.Contains(s.HotkeyProblem, "declined") {
			v.retryKeys.Show()
		} else {
			v.retryKeys.Hide()
		}
	} else {
		v.banner.Hide()
	}

	if s.WebsiteURL != "" {
		if u, err := url.Parse(s.WebsiteURL); err == nil {
			v.website.SetURL(u)
			v.website.Show()
		}
	}

	v.updateActivity(s.Activity)
}

func (v *readyView) updateActivity(entries []app.Activity) {
	if len(entries) == v.activityLen && (len(entries) == 0 || entries[0].Time.Equal(v.activityTop)) {
		return
	}
	v.activityLen = len(entries)
	if len(entries) > 0 {
		v.activityTop = entries[0].Time
	}
	v.emptyNote.Hidden = len(entries) > 0

	rows := make([]fyne.CanvasObject, 0, len(entries))
	for _, a := range entries {
		when := widget.NewLabelWithStyle(a.Time.Format("15:04"), fyne.TextAlignLeading, fyne.TextStyle{Monospace: true})
		when.Importance = widget.LowImportance
		text := widget.NewLabel(wrapText(a.Title+" · "+a.Body, activityWrap))
		if strings.Contains(strings.ToLower(a.Title), "fail") {
			text.Importance = widget.DangerImportance
		}
		rows = append(rows, container.NewBorder(nil, nil, when, nil, text))
	}
	v.activity.Objects = rows
	v.activity.Refresh()
	v.emptyNote.Refresh()
}

func hostOf(server string) string {
	if u, err := url.Parse(server); err == nil && u.Host != "" {
		return u.Host
	}
	return server
}

func capitalise(s string) string {
	if s == "" {
		return s
	}
	return strings.ToUpper(s[:1]) + s[1:]
}
