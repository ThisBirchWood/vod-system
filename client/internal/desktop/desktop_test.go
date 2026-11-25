package desktop

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/config"
)

func testEnv(t *testing.T, goos string) Env {
	dir := t.TempDir()
	return Env{
		GOOS:       goos,
		Home:       dir,
		ConfigHome: filepath.Join(dir, ".config"),
		DataHome:   filepath.Join(dir, ".local", "share"),
		Exec:       "/home/u/Apps/VoD Client.AppImage",
	}
}

func TestLinuxAutostart(t *testing.T) {
	e := testEnv(t, "linux")
	if e.AutostartEnabled() {
		t.Fatal("should start disabled")
	}
	if err := e.SetAutostart(true); err != nil {
		t.Fatal(err)
	}
	if !e.AutostartEnabled() {
		t.Fatal("should be enabled")
	}
	data, err := os.ReadFile(filepath.Join(e.ConfigHome, "autostart", config.AppID+".desktop"))
	if err != nil {
		t.Fatal(err)
	}
	entry := string(data)
	for _, want := range []string{
		"[Desktop Entry]",
		`Exec="/home/u/Apps/VoD Client.AppImage" --hidden`,
		"X-GNOME-Autostart-enabled=true",
		"Icon=" + config.AppID,
	} {
		if !strings.Contains(entry, want) {
			t.Errorf("autostart entry missing %q:\n%s", want, entry)
		}
	}

	if err := e.SetAutostart(false); err != nil {
		t.Fatal(err)
	}
	if e.AutostartEnabled() {
		t.Error("should be disabled")
	}
	if err := e.SetAutostart(false); err != nil {
		t.Errorf("disabling twice should be fine: %v", err)
	}
}

func TestDarwinAutostart(t *testing.T) {
	e := testEnv(t, "darwin")
	e.Exec = "/Applications/VoD Client.app/Contents/MacOS/vod-client"
	if err := e.SetAutostart(true); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(e.Home, "Library", "LaunchAgents", config.AppID+".plist"))
	if err != nil {
		t.Fatal(err)
	}
	plist := string(data)
	for _, want := range []string{"<string>" + config.AppID + "</string>", "<string>" + e.Exec + "</string>", "<string>--hidden</string>", "<key>RunAtLoad</key>"} {
		if !strings.Contains(plist, want) {
			t.Errorf("plist missing %q:\n%s", want, plist)
		}
	}
	if !e.AutostartEnabled() {
		t.Error("should be enabled")
	}
}

func TestInstallMenuEntry(t *testing.T) {
	e := testEnv(t, "linux")
	icon := []byte("\x89PNG fake")
	if err := e.InstallMenuEntry(icon); err != nil {
		t.Fatal(err)
	}
	entry, err := os.ReadFile(filepath.Join(e.DataHome, "applications", config.AppID+".desktop"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(entry), `Exec="/home/u/Apps/VoD Client.AppImage"`+"\n") {
		t.Errorf("menu entry should launch the app without --hidden:\n%s", entry)
	}
	if strings.Contains(string(entry), "Autostart") {
		t.Error("menu entry is not an autostart entry")
	}
	got, _ := os.ReadFile(filepath.Join(e.DataHome, "icons", "hicolor", "256x256", "apps", config.AppID+".png"))
	if string(got) != string(icon) {
		t.Error("icon not installed")
	}

	// Moving the AppImage and relaunching updates the entry.
	e.Exec = "/opt/vod-client"
	e.InstallMenuEntry(icon)
	entry, _ = os.ReadFile(filepath.Join(e.DataHome, "applications", config.AppID+".desktop"))
	if !strings.Contains(string(entry), "Exec=/opt/vod-client\n") {
		t.Errorf("entry not updated:\n%s", entry)
	}
}

func TestInstallMenuEntryNoopElsewhere(t *testing.T) {
	for _, goos := range []string{"windows", "darwin"} {
		e := testEnv(t, goos)
		if err := e.InstallMenuEntry([]byte("x")); err != nil {
			t.Fatal(err)
		}
		if _, err := os.Stat(e.DataHome); !os.IsNotExist(err) {
			t.Errorf("%s: should not write freedesktop files", goos)
		}
	}
}

func TestQuoteDesktopExec(t *testing.T) {
	tests := []struct{ in, want string }{
		{"/usr/bin/vod-client", "/usr/bin/vod-client"},
		{"--hidden", "--hidden"},
		{"/home/u/My Apps/vod", `"/home/u/My Apps/vod"`},
		{`/tmp/a"b`, `"/tmp/a\"b"`},
		{"/tmp/$HOME", `"/tmp/\$HOME"`},
		{"/tmp/100%", `"/tmp/100%%"`},
		{"", `""`},
	}
	for _, tt := range tests {
		if got := quoteDesktopExec(tt.in); got != tt.want {
			t.Errorf("quoteDesktopExec(%q) = %s, want %s", tt.in, got, tt.want)
		}
	}
}

func TestExecPathPrefersAppImage(t *testing.T) {
	t.Setenv("APPIMAGE", "/home/u/VoD-Client-x86_64.AppImage")
	got, err := ExecPath()
	if err != nil || got != "/home/u/VoD-Client-x86_64.AppImage" {
		t.Errorf("ExecPath() = %q, %v", got, err)
	}
	t.Setenv("APPIMAGE", "")
	got, err = ExecPath()
	if err != nil || got == "" {
		t.Errorf("ExecPath() = %q, %v", got, err)
	}
}

func TestLaunchAgentEscapesPath(t *testing.T) {
	if !strings.Contains(launchAgent("/Apps/Tom & Jerry/vod"), "Tom &amp; Jerry") {
		t.Error("plist path must be XML-escaped")
	}
}
