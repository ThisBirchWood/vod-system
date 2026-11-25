// Package desktop integrates the app with the user's desktop: start on login, the Linux
// applications menu, and whether a system tray is available.
package desktop

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/config"
)

// HiddenFlag starts the app straight into the tray; autostart entries pass it.
const HiddenFlag = "--hidden"

// Env describes where desktop integration files live, so tests can point it at a temp dir.
type Env struct {
	GOOS       string
	Home       string
	ConfigHome string // XDG_CONFIG_HOME
	DataHome   string // XDG_DATA_HOME
	// Exec is the path that launches the app.
	Exec string
}

// HostEnv describes the real desktop.
func HostEnv() (Env, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return Env{}, err
	}
	exec, err := ExecPath()
	if err != nil {
		return Env{}, err
	}
	env := Env{
		GOOS:       runtime.GOOS,
		Home:       home,
		ConfigHome: os.Getenv("XDG_CONFIG_HOME"),
		DataHome:   os.Getenv("XDG_DATA_HOME"),
		Exec:       exec,
	}
	if env.ConfigHome == "" {
		env.ConfigHome = filepath.Join(home, ".config")
	}
	if env.DataHome == "" {
		env.DataHome = filepath.Join(home, ".local", "share")
	}
	return env, nil
}

// ExecPath returns the path that relaunches this app. Inside an AppImage the binary runs from
// a temporary mount, so the AppImage file itself is what must be launched.
func ExecPath() (string, error) {
	if appimage := os.Getenv("APPIMAGE"); appimage != "" {
		return appimage, nil
	}
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	if resolved, err := filepath.EvalSymlinks(exe); err == nil {
		exe = resolved
	}
	return exe, nil
}

func (e Env) autostartPath() string {
	switch e.GOOS {
	case "darwin":
		return filepath.Join(e.Home, "Library", "LaunchAgents", config.AppID+".plist")
	default:
		return filepath.Join(e.ConfigHome, "autostart", config.AppID+".desktop")
	}
}

// SetAutostart makes the app start (hidden in the tray) when the user logs in, or stops it.
func (e Env) SetAutostart(enabled bool) error {
	if e.GOOS == "windows" {
		return setRunKey(config.AppName, quoteWindows(e.Exec)+" "+HiddenFlag, enabled)
	}
	path := e.autostartPath()
	if !enabled {
		if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		return nil
	}
	var content string
	if e.GOOS == "darwin" {
		content = launchAgent(e.Exec)
	} else {
		content = desktopEntry(e.Exec, []string{HiddenFlag}, true)
	}
	return writeFile(path, content)
}

// AutostartEnabled reports whether the app is set to start on login.
func (e Env) AutostartEnabled() bool {
	if e.GOOS == "windows" {
		return runKeyExists(config.AppName)
	}
	_, err := os.Stat(e.autostartPath())
	return err == nil
}

// InstallMenuEntry adds the app to the Linux applications menu, pointing at its current
// location (an AppImage may have moved since the last run). GNOME also needs this entry before
// it will grant global shortcuts to the app ID. It does nothing on other platforms, whose
// installers handle this.
func (e Env) InstallMenuEntry(icon []byte) error {
	if e.GOOS != "linux" && e.GOOS != "freebsd" && e.GOOS != "openbsd" {
		return nil
	}
	iconPath := filepath.Join(e.DataHome, "icons", "hicolor", "256x256", "apps", config.AppID+".png")
	if err := os.MkdirAll(filepath.Dir(iconPath), 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(iconPath, icon, 0o644); err != nil {
		return err
	}
	return writeFile(filepath.Join(e.DataHome, "applications", config.AppID+".desktop"), desktopEntry(e.Exec, nil, false))
}

func writeFile(path, content string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, []byte(content), 0o644)
}

// desktopEntry renders a freedesktop .desktop file.
func desktopEntry(exec string, args []string, autostart bool) string {
	cmd := []string{quoteDesktopExec(exec)}
	for _, a := range args {
		cmd = append(cmd, quoteDesktopExec(a))
	}
	var b strings.Builder
	fmt.Fprintf(&b, "[Desktop Entry]\n")
	fmt.Fprintf(&b, "Type=Application\n")
	fmt.Fprintf(&b, "Name=%s\n", config.AppName)
	fmt.Fprintf(&b, "Comment=Clip your stream with hotkeys\n")
	fmt.Fprintf(&b, "Exec=%s\n", strings.Join(cmd, " "))
	fmt.Fprintf(&b, "Icon=%s\n", config.AppID)
	fmt.Fprintf(&b, "Terminal=false\n")
	fmt.Fprintf(&b, "Categories=AudioVideo;Video;Recorder;\n")
	fmt.Fprintf(&b, "StartupNotify=false\n")
	if autostart {
		fmt.Fprintf(&b, "X-GNOME-Autostart-enabled=true\n")
	}
	return b.String()
}

// quoteDesktopExec quotes an Exec argument per the Desktop Entry spec.
func quoteDesktopExec(arg string) string {
	if arg != "" && !strings.ContainsAny(arg, " \t\n\"'\\><~|&;$*?#()`=%") {
		return arg
	}
	var b strings.Builder
	b.WriteByte('"')
	for _, r := range arg {
		switch r {
		case '"', '`', '$', '\\':
			b.WriteByte('\\')
		case '%':
			b.WriteByte('%') // %% is a literal percent
		}
		b.WriteRune(r)
	}
	b.WriteByte('"')
	return b.String()
}

// launchAgent renders a macOS LaunchAgent that starts the app at login.
func launchAgent(exec string) string {
	return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key>
	<string>` + config.AppID + `</string>
	<key>ProgramArguments</key>
	<array>
		<string>` + xmlEscape(exec) + `</string>
		<string>` + HiddenFlag + `</string>
	</array>
	<key>RunAtLoad</key>
	<true/>
</dict>
</plist>
`
}

func xmlEscape(s string) string {
	return strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", `"`, "&quot;").Replace(s)
}

func quoteWindows(path string) string { return `"` + path + `"` }
